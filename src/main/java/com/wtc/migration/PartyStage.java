package com.wtc.migration;

import com.wtc.customer.application.PartyService;
import com.wtc.customer.domain.Masking;
import com.wtc.shared.security.SecretCipher;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Expéditeurs (fiches avec {@code customeralt}, et titulaires qui ont envoyé) et bénéficiaires
 * ({@code contactcustomer}) avec leur compte bancaire. Aucune fusion de personnes : deux fiches
 * identiques restent deux fiches.
 */
final class PartyStage {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EtlContext ctx;
    private final SecretCipher cipher;
    private final DestinationFactory destinations;

    PartyStage(EtlContext ctx, SecretCipher cipher, DestinationFactory destinations) {
        this.ctx = ctx;
        this.cipher = cipher;
        this.destinations = destinations;
    }

    void run() {
        ctx.report.stage("parties");
        senders();
        beneficiaries();
    }

    private void senders() {
        for (Row c :
                ctx.rows(
                        """
                        SELECT * FROM customer
                        WHERE customeralt IS NOT NULL OR id IN (SELECT DISTINCT customer_id FROM transaction WHERE customer_id IS NOT NULL)
                        ORDER BY id
                        """)) {
            EtlContext.Ref holder = ctx.holderByLegacyId.get(c.idOrZero());
            if (holder == null) {
                continue; // déjà rapporté à l'étape clients
            }
            Identity identity = Identity.of(c, ctx.report, "customer");
            if (identity == null) {
                ctx.report.error(
                        "customer",
                        c.idOrZero(),
                        "Expéditeur sans nom exploitable : non migré",
                        c.forReport());
                ctx.report.count("senders.rejected");
                continue;
            }
            String iso =
                    c.id("country_id") == null
                            ? null
                            : ctx.countryByLegacyId.get(c.id("country_id"));
            String typeText =
                    c.str("typeidentification") != null
                            ? c.str("typeidentification")
                            : c.str("id_type");
            LocalDate idExpiry =
                    c.instant("expireddatepiece") == null
                            ? null
                            : c.instant("expireddatepiece").atZone(ZoneOffset.UTC).toLocalDate();
            Map<String, Object> attrs = new LinkedHashMap<>();
            for (String k : new String[] {"customeralt", "sender_code", "motif", "partner_id"}) {
                if (c.str(k) != null) {
                    attrs.put(k, c.str(k));
                }
            }
            EtlContext.Ref party =
                    insert(
                            "SENDER",
                            "customer",
                            c,
                            holder,
                            identity,
                            iso,
                            c.str("numeropiece"),
                            typeText,
                            idExpiry,
                            Parse.date(c.str("date_of_birth"), ctx.today).orElse(null),
                            attrs,
                            c.str("nationalite"),
                            false);
            if (party != null) {
                ctx.senderPartyByLegacyId.put(c.idOrZero(), party);
                ctx.report.count("parties.senders");
            }
        }
    }

    private void beneficiaries() {
        // Pays le plus fréquent des transactions de chaque bénéficiaire : sert quand la fiche n'a
        // pas de pays.
        Map<Long, String> countryFromTransactions = new java.util.HashMap<>();
        for (Row r :
                ctx.rows(
                        "SELECT beneficiare_id, country_id, COUNT(*) AS n FROM transaction WHERE"
                                + " beneficiare_id IS NOT NULL AND country_id IS NOT NULL GROUP BY"
                                + " beneficiare_id, country_id ORDER BY beneficiare_id, n")) {
            String iso = ctx.countryByLegacyId.get(r.id("country_id"));
            if (iso != null) {
                countryFromTransactions.put(
                        r.id("beneficiare_id"), iso); // le dernier (le plus fréquent) l'emporte
            }
        }
        for (Row c : ctx.rows("SELECT * FROM contactcustomer ORDER BY id")) {
            long legacyId = c.idOrZero();
            EtlContext.Ref holder =
                    c.id("customer_id") == null
                            ? null
                            : ctx.holderByLegacyId.get(c.id("customer_id"));
            if (holder == null) {
                ctx.report.error(
                        "contactcustomer",
                        legacyId,
                        "Bénéficiaire sans titulaire migré : non migré",
                        c.forReport("bankaccountnumber", "bankiban"));
                ctx.report.count("beneficiaries.rejected");
                continue;
            }
            Identity identity = Identity.of(c, ctx.report, "contactcustomer");
            if (identity == null) {
                ctx.report.error(
                        "contactcustomer",
                        legacyId,
                        "Bénéficiaire sans nom exploitable : non migré",
                        c.forReport("bankaccountnumber", "bankiban"));
                ctx.report.count("beneficiaries.rejected");
                continue;
            }
            String iso =
                    c.str("country_iso_code") == null
                            ? null
                            : c.str("country_iso_code").toUpperCase();
            if (iso != null && !ctx.currencyByCountry.containsKey(iso)) {
                ctx.report.warn(
                        "contactcustomer",
                        legacyId,
                        "Pays « " + iso + " » inconnu du référentiel migré : laissé vide",
                        c.forReport("bankaccountnumber", "bankiban"));
                iso = null;
            }
            if (iso == null && countryFromTransactions.get(legacyId) != null) {
                iso = countryFromTransactions.get(legacyId);
                ctx.report.info(
                        "contactcustomer",
                        legacyId,
                        "Pays du bénéficiaire déduit de ses transactions : " + iso);
            }
            LocalDate expiry =
                    c.str("expired_document") == null
                            ? null
                            : Parse.expiry(c.str("expired_document")).orElse(null);
            Map<String, Object> attrs = new LinkedHashMap<>();
            for (String k :
                    new String[] {
                        "recipienteralt", "beneficiary_code", "bankrelaction", "zipcode"
                    }) {
                if (c.str(k) != null) {
                    attrs.put(k, c.str(k));
                }
            }
            EtlContext.Ref party =
                    insert(
                            "BENEFICIARY",
                            "contactcustomer",
                            c,
                            holder,
                            identity,
                            iso,
                            c.str("id_number"),
                            c.str("id_type"),
                            expiry,
                            Parse.date(c.str("dob"), ctx.today).orElse(null),
                            attrs,
                            c.str("banknationalite"),
                            true);
            if (party == null) {
                continue;
            }
            ctx.beneficiaryPartyByLegacyId.put(legacyId, party);
            ctx.report.count("parties.beneficiaries");
            String account =
                    c.str("bankaccountnumber") != null
                            ? c.str("bankaccountnumber")
                            : c.str("bankiban");
            if (account != null) {
                if (iso == null) {
                    ctx.report.warn(
                            "contactcustomer",
                            legacyId,
                            "Compte bancaire non migré : pays du bénéficiaire inconnu (repris si"
                                    + " une transaction le précise)",
                            c.forReport("bankaccountnumber", "bankiban"));
                } else {
                    destinations.bank(
                            party.id(),
                            identity.displayName(),
                            iso,
                            account,
                            c.str("bankid"),
                            c.str("bankname"),
                            c.str("bankswiftcode"),
                            c.str("bankifsccode"),
                            c.str("bankroutingnumber"),
                            false);
                    ctx.report.count("destinations.from_contacts");
                }
            }
        }
    }

    /**
     * Insère une partie ; les identifiants de pièce sont chiffrés avec le contexte que l'API
     * utilise pour les relire.
     */
    private EtlContext.Ref insert(
            String role,
            String source,
            Row c,
            EtlContext.Ref holder,
            Identity identity,
            String country,
            String idNumber,
            String idType,
            LocalDate idExpiry,
            LocalDate birth,
            Map<String, Object> attrs,
            String nationality,
            boolean contact) {
        UUID publicId = UUID.randomUUID();
        String phone =
                Parse.phone(
                                c.str("phone"),
                                country == null ? null : ctx.callingCodeByCountry.get(country))
                        .orElse(null);
        if (c.str("phone") != null && phone == null) {
            ctx.report.warn(
                    source,
                    c.idOrZero(),
                    "Téléphone inexploitable, non migré : « " + c.str("phone") + " »",
                    c.forReport("bankaccountnumber", "bankiban"));
        }
        if (contact && c.str("dob") != null && birth == null) {
            ctx.report.warn(
                    source,
                    c.idOrZero(),
                    "Date de naissance inexploitable : « " + c.str("dob") + " »",
                    c.forReport("bankaccountnumber", "bankiban"));
        }
        String number = idNumber == null ? null : idNumber.strip();
        byte[] encrypted =
                number == null ? null : cipher.encrypt(number, PartyService.context(publicId));
        EtlContext.Ref[] out = new EtlContext.Ref[1];
        boolean ok =
                ctx.attempt(
                        () ->
                                out[0] =
                                        ctx.target
                                                .sql(
                                                        """
                                                        INSERT INTO parties (public_id, customer_id, role, kind, first_name, last_name, business_name, business_type,
                                                            business_registered_on, birth_date, nationality, occupation, gender, civility, address_line, city,
                                                            country_code, phone_e164, email, id_document_type, id_document_number_enc, id_document_last4,
                                                            id_document_expires_on, legacy_id, legacy_source, legacy_attributes)
                                                        VALUES (:pid, :customer, :role, :kind, :first, :last, :bname, :btype, :registered, :birth, :nat, :occ, :gender,
                                                            :civ, :address, :city, :country, :phone, :email, :idtype, :idenc, :last4, :idexp, :legacy, :source,
                                                            CAST(:attrs AS jsonb))
                                                        ON CONFLICT (legacy_source, legacy_id) DO UPDATE SET first_name = EXCLUDED.first_name
                                                        RETURNING id, public_id
                                                        """)
                                                .param("pid", publicId)
                                                .param("customer", holder.id())
                                                .param("role", role)
                                                .param(
                                                        "kind",
                                                        identity.business()
                                                                ? "BUSINESS"
                                                                : "INDIVIDUAL")
                                                .param("first", identity.firstName())
                                                .param("last", identity.lastName())
                                                .param("bname", identity.businessName())
                                                .param(
                                                        "btype",
                                                        identity.business()
                                                                ? Parse.clean(c.str("bussinestype"))
                                                                : null)
                                                .param(
                                                        "registered",
                                                        date(
                                                                Parse.date(
                                                                                c.str(
                                                                                        "regiter_business_date"),
                                                                                ctx.today)
                                                                        .orElse(null)))
                                                .param("birth", date(birth))
                                                .param("nat", Parse.clean(nationality))
                                                .param("occ", Parse.clean(c.str("occupation")))
                                                .param("gender", Parse.gender(c.str("gender")))
                                                .param("civ", Parse.clean(c.str("civility")))
                                                .param("address", Parse.clean(c.str("address")))
                                                .param("city", Parse.clean(c.str("city")))
                                                .param("country", country)
                                                .param("phone", phone)
                                                .param(
                                                        "email",
                                                        c.str("email") != null
                                                                        && c.str("email")
                                                                                .contains("@")
                                                                ? c.str("email").toLowerCase()
                                                                : null)
                                                .param("idtype", Parse.clean(idType))
                                                .param("idenc", encrypted)
                                                .param("last4", Masking.last4(number))
                                                .param("idexp", date(idExpiry))
                                                .param("legacy", c.idOrZero())
                                                .param("source", source)
                                                .param("attrs", JSON.writeValueAsString(attrs))
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
                    source,
                    c.idOrZero(),
                    "Partie refusée par le schéma : " + ctx.lastError,
                    c.forReport("bankaccountnumber", "bankiban", "numeropiece", "id_number"));
            ctx.report.count(role.equals("SENDER") ? "senders.rejected" : "beneficiaries.rejected");
            return null;
        }
        return out[0];
    }

    private static Date date(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }
}
