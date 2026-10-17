package com.wtc.migration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Comptes d'accès. Le hachage bcrypt est repris tel quel (le nouveau système sait le vérifier puis
 * le remplacer par un Argon2id à la première connexion). Les rôles d'origine sont normalisés avec
 * le <b>moindre privilège</b> en cas de doute.
 */
final class UserStage {

    static final String MIGRATION_EMAIL = "migration@wtc.invalid";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final EtlContext ctx;

    UserStage(EtlContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        ctx.report.stage("users");
        migrationUser();
        Map<Long, String> callingByAccount = new HashMap<>();
        Set<Long> accountsWithCustomer = new HashSet<>();
        for (Row c :
                ctx.rows(
                        "SELECT compte_id, country_id FROM customer WHERE compte_id IS NOT NULL")) {
            accountsWithCustomer.add(c.id("compte_id"));
            String iso =
                    c.id("country_id") == null
                            ? null
                            : ctx.countryByLegacyId.get(c.id("country_id"));
            if (iso != null) {
                callingByAccount.putIfAbsent(c.id("compte_id"), ctx.callingCodeByCountry.get(iso));
            }
        }
        Set<String> emails = new HashSet<>();
        Set<String> phones = new HashSet<>();
        for (Row u :
                ctx.rows(
                        "SELECT id, email, name, lastname, roles, password, country_code, phone,"
                                + " country, photo FROM user ORDER BY id")) {
            long legacyId = u.idOrZero();
            String email = u.str("email") == null ? null : u.str("email").toLowerCase(Locale.ROOT);
            if (email == null || !EMAIL.matcher(email).matches()) {
                reject(u, "Adresse e-mail absente ou invalide");
                continue;
            }
            if (!emails.add(email)) {
                reject(u, "Adresse e-mail en double (insensible à la casse) : compte non migré");
                continue;
            }
            String name =
                    Parse.clean(
                            ((u.str("name") == null ? "" : u.str("name"))
                                            + " "
                                            + (u.str("lastname") == null ? "" : u.str("lastname")))
                                    .strip());
            if (name == null) {
                name = email;
            }
            String phone = Parse.phone(u.str("phone"), callingByAccount.get(legacyId)).orElse(null);
            if (u.str("phone") != null && phone == null) {
                ctx.report.warn(
                        "user",
                        legacyId,
                        "Téléphone inexploitable, non migré : « " + u.str("phone") + " »",
                        u.forReport("password", "pin_code"));
            } else if (phone != null && !phones.add(phone)) {
                ctx.report.warn(
                        "user",
                        legacyId,
                        "Téléphone déjà utilisé par un autre compte, non migré : " + phone,
                        u.forReport("password"));
                phone = null;
            }
            String hash = u.str("password");
            String status = "ACTIVE";
            if (hash == null || !hash.startsWith("$2")) {
                ctx.report.error(
                        "user",
                        legacyId,
                        "Mot de passe absent ou format inconnu : compte suspendu, réinitialisation"
                                + " nécessaire",
                        u.forReport("password"));
                hash = "!no-password";
                status = "SUSPENDED";
            }
            UUID publicId = UUID.randomUUID();
            String finalPhone = phone;
            String finalName = name;
            String finalHash = hash;
            String finalStatus = status;
            Long[] id = new Long[1];
            boolean ok =
                    ctx.attempt(
                            () -> {
                                var row =
                                        ctx.target
                                                .sql(
                                                        """
                                                        INSERT INTO users (public_id, email, phone_e164, password_hash, display_name, status, legacy_id)
                                                        VALUES (:pid, :email, :phone, :hash, :name, :status, :legacy)
                                                        ON CONFLICT (legacy_id) DO UPDATE SET email = EXCLUDED.email, phone_e164 = EXCLUDED.phone_e164,
                                                            display_name = EXCLUDED.display_name
                                                        RETURNING id, public_id
                                                        """)
                                                .param("pid", publicId)
                                                .param("email", email)
                                                .param("phone", finalPhone)
                                                .param("hash", finalHash)
                                                .param("name", finalName)
                                                .param("status", finalStatus)
                                                .param("legacy", legacyId)
                                                .query(
                                                        (rs, n) ->
                                                                new EtlContext.Ref(
                                                                        rs.getLong("id"),
                                                                        rs.getObject(
                                                                                "public_id",
                                                                                UUID.class)))
                                                .single();
                                ctx.userByLegacyId.put(legacyId, row);
                                id[0] = row.id();
                            });
            if (!ok) {
                reject(u, "Refusé par le schéma : " + ctx.lastError);
                continue;
            }
            roles(u, id[0], accountsWithCustomer.contains(legacyId));
            ctx.report.count("users.migrated");
        }
        Long photos =
                ctx
                        .rows(
                                "SELECT id FROM user WHERE photo IS NOT NULL AND photo NOT IN"
                                        + " ('image', '')")
                        .stream()
                        .count();
        if (photos > 0) {
            ctx.report.info("user", null, photos + " photos de profil à copier (étape fichiers)");
        }
    }

    private void migrationUser() {
        ctx.target
                .sql(
                        """
                        INSERT INTO users (email, password_hash, display_name, status) VALUES (:email, '!disabled', 'Migration (système)', 'SUSPENDED')
                        ON CONFLICT DO NOTHING
                        """)
                .param("email", MIGRATION_EMAIL)
                .update();
        var ref =
                ctx.target
                        .sql("SELECT id, public_id FROM users WHERE lower(email) = :e")
                        .param("e", MIGRATION_EMAIL)
                        .query(
                                (rs, n) ->
                                        new EtlContext.Ref(
                                                rs.getLong("id"),
                                                rs.getObject("public_id", UUID.class)))
                        .single();
        ctx.migrationUserId = ref.id();
        ctx.migrationUserPublicId = ref.publicId();
    }

    private void roles(Row u, long userId, boolean hasCustomer) {
        Set<String> legacy = Parse.roles(u.str("roles"));
        Set<String> target = new LinkedHashSet<>();
        for (String role : legacy) {
            switch (role) {
                case "ROLE_ADMIN" -> target.add("ADMIN");
                case "ROLE_SUPER" -> {
                    target.add("ADMIN");
                    ctx.report.warn(
                            "user",
                            u.idOrZero(),
                            "ROLE_SUPER migré en ADMIN (moindre privilège) : à promouvoir en"
                                    + " SUPER_ADMIN si c'est voulu",
                            u.forReport("password", "pin_code"));
                }
                case "ROLE_SUPER_ADMIN" -> target.add("SUPER_ADMIN");
                case "ROLE_SUPPORT" -> target.add("SUPPORT");
                case "ROLE_CUSTOMER", "ROLE_USER" -> {
                    if (hasCustomer) {
                        target.add("CUSTOMER");
                    }
                }
                default ->
                        ctx.report.warn(
                                "user",
                                u.idOrZero(),
                                "Rôle inconnu ignoré : " + role,
                                u.forReport("password", "pin_code"));
            }
        }
        if (target.isEmpty()) {
            ctx.report.warn(
                    "user",
                    u.idOrZero(),
                    "Compte migré sans aucun rôle (pas de fiche client, rôle client seul) : il ne"
                            + " pourra rien faire",
                    u.forReport("password", "pin_code"));
        }
        for (String role : target) {
            ctx.target
                    .sql(
                            "INSERT INTO user_roles (user_id, role_code) VALUES (:u, :r) ON"
                                    + " CONFLICT DO NOTHING")
                    .param("u", userId)
                    .param("r", role)
                    .update();
        }
    }

    private void reject(Row u, String message) {
        ctx.report.error("user", u.idOrZero(), message, u.forReport("password", "pin_code"));
        ctx.report.count("users.rejected");
    }
}
