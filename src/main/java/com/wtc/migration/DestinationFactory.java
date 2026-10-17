package com.wtc.migration;

import com.wtc.customer.application.DestinationService;
import com.wtc.customer.domain.Masking;
import com.wtc.shared.security.SecretCipher;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Crée les destinations de paiement (compte bancaire ou portefeuille mobile) : numéro chiffré avec
 * le contexte que l'API utilise pour le relire, banque ou opérateur rapprochés des référentiels ou,
 * à défaut, une entrée « migrée » par pays (à relier à la liste de Wace après son import).
 */
final class DestinationFactory {

    static final String UNKNOWN_BANK = "Banque inconnue (migrée)";

    private final EtlContext ctx;
    private final SecretCipher cipher;
    private final Map<String, Long> created = new HashMap<>();

    DestinationFactory(EtlContext ctx, SecretCipher cipher) {
        this.ctx = ctx;
        this.cipher = cipher;
    }

    /**
     * @return l'identifiant de la destination (existante pour la même partie, le même numéro et le
     *     même organisme, sinon créée)
     */
    long bank(
            long partyId,
            String partyName,
            String country,
            String number,
            String wacebankId,
            String bankName,
            String swift,
            String ifsc,
            String routing,
            boolean archived) {
        String key =
                partyId
                        + "|B|"
                        + number
                        + "|"
                        + (bankName == null ? "" : bankName.toLowerCase(Locale.ROOT));
        Long existing = created.get(key);
        if (existing != null) {
            return existing;
        }
        long bankId = resolveBank(country, wacebankId, bankName);
        long id =
                insert(
                        partyId,
                        partyName,
                        "BANK_ACCOUNT",
                        country,
                        bankId,
                        null,
                        number,
                        swift,
                        ifsc,
                        routing,
                        archived);
        created.put(key, id);
        return id;
    }

    long mobile(
            long partyId,
            String partyName,
            String country,
            String number,
            String operatorLabel,
            boolean archived) {
        String key =
                partyId
                        + "|M|"
                        + number
                        + "|"
                        + (operatorLabel == null ? "" : operatorLabel.toLowerCase(Locale.ROOT));
        Long existing = created.get(key);
        if (existing != null) {
            return existing;
        }
        long operatorId = resolveOperator(country, operatorLabel);
        long id =
                insert(
                        partyId,
                        partyName,
                        "MOBILE_WALLET",
                        country,
                        null,
                        operatorId,
                        number,
                        null,
                        null,
                        null,
                        archived);
        created.put(key, id);
        return id;
    }

    private long insert(
            long partyId,
            String partyName,
            String kind,
            String country,
            Long bankId,
            Long operatorId,
            String number,
            String swift,
            String ifsc,
            String routing,
            boolean archived) {
        UUID publicId = UUID.randomUUID();
        byte[] encrypted = cipher.encrypt(number, DestinationService.context(publicId));
        return ctx.target
                .sql(
                        """
                        INSERT INTO payout_destinations (public_id, party_id, kind, country_code, bank_id, mobile_operator_id, account_holder_name,
                            account_number_enc, account_number_masked, swift_code, ifsc_code, routing_number, archived_at)
                        VALUES (:pid, :party, :kind, :country, :bank, :operator, :holder, :enc, :masked, :swift, :ifsc, :routing,
                            CASE WHEN :archived THEN now() END)
                        RETURNING id
                        """)
                .param("pid", publicId)
                .param("party", partyId)
                .param("kind", kind)
                .param("country", country)
                .param("bank", bankId)
                .param("operator", operatorId)
                .param("holder", partyName)
                .param("enc", encrypted)
                .param("masked", Masking.mask(number))
                .param("swift", swift)
                .param("ifsc", ifsc)
                .param("routing", routing)
                .param("archived", archived)
                .query(Long.class)
                .single();
    }

    private long resolveBank(String country, String wacebankId, String bankName) {
        if (wacebankId != null) {
            var byWace =
                    ctx.target
                            .sql(
                                    "SELECT id FROM banks WHERE country_code = :c AND wace_bank_id"
                                            + " = :w")
                            .param("c", country)
                            .param("w", wacebankId)
                            .query(Long.class)
                            .optional();
            if (byWace.isPresent()) {
                return byWace.get();
            }
        }
        if (bankName != null) {
            var byName =
                    ctx.target
                            .sql(
                                    "SELECT id FROM banks WHERE country_code = :c AND lower(name) ="
                                            + " lower(:n) LIMIT 1")
                            .param("c", country)
                            .param("n", bankName)
                            .query(Long.class)
                            .optional();
            if (byName.isPresent()) {
                return byName.get();
            }
        }
        return placeholder("banks", country, UNKNOWN_BANK);
    }

    private long resolveOperator(String country, String label) {
        if (label != null) {
            var found =
                    ctx.target
                            .sql(
                                    "SELECT id FROM mobile_operators WHERE country_code = :c AND"
                                            + " (lower(name) = lower(:n) OR lower(short_code) ="
                                            + " lower(:n)) LIMIT 1")
                            .param("c", country)
                            .param("n", label)
                            .query(Long.class)
                            .optional();
            if (found.isPresent()) {
                return found.get();
            }
        }
        return placeholder(
                "mobile_operators",
                country,
                (label == null ? "Opérateur inconnu" : label) + " (migré)");
    }

    private long placeholder(String table, String country, String name) {
        var found =
                ctx.target
                        .sql("SELECT id FROM " + table + " WHERE country_code = :c AND name = :n")
                        .param("c", country)
                        .param("n", name)
                        .query(Long.class)
                        .optional();
        if (found.isPresent()) {
            return found.get();
        }
        String sql =
                "banks".equals(table)
                        ? "INSERT INTO banks (country_code, name, enabled) VALUES (:c, :n, false)"
                                + " RETURNING id"
                        : "INSERT INTO mobile_operators (country_code, name, enabled) VALUES (:c,"
                                + " :n, false) RETURNING id";
        ctx.report.count(table + ".placeholders");
        return ctx.target.sql(sql).param("c", country).param("n", name).query(Long.class).single();
    }
}
