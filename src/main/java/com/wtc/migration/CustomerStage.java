package com.wtc.migration;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * Titulaires de compte (fiches « client » sans {@code customeralt}). Les fiches avec {@code
 * customeralt} sont des expéditeurs enregistrés par un titulaire : elles deviennent des parties à
 * l'étape suivante, mais pointent déjà vers leur titulaire.
 */
final class CustomerStage {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EtlContext ctx;

    CustomerStage(EtlContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        ctx.report.stage("customers");
        Set<Long> linkedAccounts = new HashSet<>();
        for (Row c : ctx.rows("SELECT * FROM customer WHERE customeralt IS NULL ORDER BY id")) {
            migrate(c, linkedAccounts);
        }
        // Expéditeurs enregistrés par un titulaire : rattachés à leur titulaire.
        for (Row c :
                ctx.rows(
                        "SELECT id, customeralt FROM customer WHERE customeralt IS NOT NULL ORDER"
                                + " BY id")) {
            EtlContext.Ref holder = ctx.holderByLegacyId.get(c.id("customeralt"));
            if (holder == null) {
                ctx.report.error(
                        "customer",
                        c.idOrZero(),
                        "Expéditeur dont le titulaire "
                                + c.id("customeralt")
                                + " n'est pas migré : non migré",
                        c.forReport());
                ctx.report.count("senders.rejected");
            } else {
                ctx.holderByLegacyId.put(c.idOrZero(), holder);
            }
        }
    }

    private void migrate(Row c, Set<Long> linkedAccounts) {
        long legacyId = c.idOrZero();
        Identity identity = Identity.of(c, ctx.report, "customer");
        if (identity == null) {
            ctx.report.error(
                    "customer",
                    legacyId,
                    "Client sans nom exploitable : non migré (les transactions liées seront"
                            + " rejetées)",
                    c.forReport());
            ctx.report.count("customers.rejected");
            return;
        }
        String iso =
                c.id("country_id") == null ? null : ctx.countryByLegacyId.get(c.id("country_id"));
        String phone =
                Parse.phone(c.str("phone"), iso == null ? null : ctx.callingCodeByCountry.get(iso))
                        .orElse(null);
        if (c.str("phone") != null && phone == null) {
            ctx.report.warn(
                    "customer",
                    legacyId,
                    "Téléphone inexploitable, non migré : « " + c.str("phone") + " »",
                    c.forReport());
        }
        String email =
                c.str("email") != null && EMAIL.matcher(c.str("email")).matches()
                        ? c.str("email").toLowerCase(Locale.ROOT)
                        : null;
        if (c.str("email") != null && email == null) {
            ctx.report.warn(
                    "customer",
                    legacyId,
                    "E-mail invalide, non migré : « " + c.str("email") + " »",
                    c.forReport());
        }
        LocalDate birth = date(c, "date_of_birth", legacyId);
        LocalDate registered = date(c, "regiter_business_date", legacyId);
        Long accountLegacy = c.id("compte_id");
        EtlContext.Ref user = accountLegacy == null ? null : ctx.userByLegacyId.get(accountLegacy);
        if (accountLegacy != null && user == null) {
            ctx.report.warn(
                    "customer",
                    legacyId,
                    "Compte d'accès " + accountLegacy + " non migré : client créé sans accès",
                    c.forReport());
        } else if (user != null && !linkedAccounts.add(user.id())) {
            ctx.report.warn(
                    "customer",
                    legacyId,
                    "Compte d'accès déjà rattaché à un autre client : client créé sans accès",
                    c.forReport());
            user = null;
        }
        Map<String, Object> legacyAttributes = new LinkedHashMap<>();
        for (String k :
                new String[] {
                    "partner_id", "isagent", "sender_code", "motif", "id_type", "typeidentification"
                }) {
            if (c.str(k) != null) {
                legacyAttributes.put(k, c.str(k));
            }
        }
        UUID publicId = UUID.randomUUID();
        String fFirst = identity.firstName();
        String fLast = identity.lastName();
        String fBusinessName = identity.businessName();
        boolean fBusiness = identity.business();
        EtlContext.Ref fUser = user;
        EtlContext.Ref[] created = new EtlContext.Ref[1];
        boolean ok =
                ctx.attempt(
                        () ->
                                created[0] =
                                        ctx.target
                                                .sql(
                                                        """
                                                        INSERT INTO customers (public_id, user_id, kind, first_name, last_name, business_name, business_type,
                                                            business_registered_on, birth_date, nationality, occupation, gender, civility, address_line, city,
                                                            country_code, phone_e164, email, legacy_id, legacy_attributes)
                                                        VALUES (:pid, :user, :kind, :first, :last, :bname, :btype, :registered, :birth, :nat, :occ, :gender, :civ,
                                                            :address, :city, :country, :phone, :email, :legacy, CAST(:attrs AS jsonb))
                                                        ON CONFLICT (legacy_id) DO UPDATE SET first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name,
                                                            business_name = EXCLUDED.business_name, phone_e164 = EXCLUDED.phone_e164, email = EXCLUDED.email
                                                        RETURNING id, public_id
                                                        """)
                                                .param("pid", publicId)
                                                .param("user", fUser == null ? null : fUser.id())
                                                .param(
                                                        "kind",
                                                        fBusiness ? "BUSINESS" : "INDIVIDUAL")
                                                .param("first", fBusiness ? null : fFirst)
                                                .param("last", fBusiness ? null : fLast)
                                                .param("bname", fBusiness ? fBusinessName : null)
                                                .param(
                                                        "btype",
                                                        fBusiness
                                                                ? Parse.clean(c.str("bussinestype"))
                                                                : null)
                                                .param(
                                                        "registered",
                                                        registered == null
                                                                ? null
                                                                : Date.valueOf(registered))
                                                .param(
                                                        "birth",
                                                        birth == null ? null : Date.valueOf(birth))
                                                .param("nat", Parse.clean(c.str("nationalite")))
                                                .param("occ", Parse.clean(c.str("occupation")))
                                                .param("gender", Parse.gender(c.str("gender")))
                                                .param("civ", Parse.clean(c.str("civility")))
                                                .param("address", Parse.clean(c.str("address")))
                                                .param("city", Parse.clean(c.str("city")))
                                                .param("country", iso)
                                                .param("phone", phone)
                                                .param("email", email)
                                                .param("legacy", legacyId)
                                                .param(
                                                        "attrs",
                                                        JSON.writeValueAsString(legacyAttributes))
                                                .query(
                                                        (rs, n) ->
                                                                new EtlContext.Ref(
                                                                        rs.getLong("id"),
                                                                        rs.getObject(
                                                                                "public_id",
                                                                                UUID.class)))
                                                .single());
        if (!ok) {
            ctx.report.error(
                    "customer",
                    legacyId,
                    "Client refusé par le schéma : " + ctx.lastError,
                    c.forReport());
            ctx.report.count("customers.rejected");
            return;
        }
        ctx.holderByLegacyId.put(legacyId, created[0]);
        ctx.report.count("customers.migrated");
    }

    private LocalDate date(Row c, String column, long legacyId) {
        String raw = c.str(column);
        if (raw == null) {
            return null;
        }
        LocalDate d = Parse.date(raw, ctx.today).orElse(null);
        if (d == null) {
            ctx.report.warn(
                    "customer",
                    legacyId,
                    "Date inexploitable dans " + column + " (« " + raw + " »), laissée vide",
                    c.forReport());
        }
        return d;
    }
}
