package com.wtc.migration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Transactions → transferts. Aucune écriture au grand livre : l'argent est déjà pris en compte dans
 * les soldes d'ouverture. Aucun remboursement n'est jamais produit ni déclenché. Une transaction
 * dont l'issue est inconnue (« en validation », « en attente ») devient {@code CANCELLED} avec un
 * motif explicite : elle est exportée pour traitement manuel.
 */
final class TransferStage {

    static final String SOURCE_CURRENCY =
            "XAF"; // hypothèse 12 : l'ancienne plateforme n'avait qu'une devise de dépôt
    private static final Set<String> PAIRS = Set.of("P2P", "P2B", "B2P", "B2B");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EtlContext ctx;
    private final DestinationFactory destinations;
    private final Map<Long, String> partyKind = new HashMap<>();
    private final Map<Long, Long> holderUser = new HashMap<>();
    private final Map<Long, ObjectNode> partyNodes = new HashMap<>();
    private final Set<String> references = new HashSet<>();
    private final Set<String> providerReferences = new HashSet<>();

    TransferStage(EtlContext ctx, DestinationFactory destinations) {
        this.ctx = ctx;
        this.destinations = destinations;
    }

    void run() {
        ctx.report.stage("transfers");
        Map<Long, Row> contacts = new HashMap<>();
        ctx.rows("SELECT * FROM contactcustomer").forEach(r -> contacts.put(r.idOrZero(), r));
        ctx.target
                .sql("SELECT id, kind FROM parties")
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getString(2)))
                .list()
                .forEach(e -> partyKind.put(e.getKey(), e.getValue()));
        ctx.target
                .sql("SELECT id, user_id FROM customers WHERE user_id IS NOT NULL")
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getLong(2)))
                .list()
                .forEach(e -> holderUser.put(e.getKey(), e.getValue()));
        for (Row t : ctx.rows("SELECT * FROM transaction ORDER BY id")) {
            try {
                if (migrate(t, contacts)) {
                    ctx.report.count("transfers.migrated");
                } else {
                    ctx.report.count("transfers.rejected");
                }
            } catch (RuntimeException e) {
                ctx.report.error(
                        "transaction",
                        t.idOrZero(),
                        "Erreur inattendue : "
                                + e.getClass().getSimpleName()
                                + " "
                                + e.getMessage(),
                        t.forReport());
                ctx.report.count("transfers.rejected");
            }
        }
    }

    private boolean reject(Row t, String message) {
        ctx.rejectedTransactions.add(t.idOrZero());
        ctx.report.error("transaction", t.idOrZero(), message, t.forReport());
        return false;
    }

    private boolean migrate(Row t, Map<Long, Row> contacts) {
        long legacyId = t.idOrZero();
        Long customerLegacy = t.id("customer_id");
        EtlContext.Ref holder =
                customerLegacy == null ? null : ctx.holderByLegacyId.get(customerLegacy);
        EtlContext.Ref sender =
                customerLegacy == null ? null : ctx.senderPartyByLegacyId.get(customerLegacy);
        if (holder == null || sender == null) {
            return reject(t, "Client ou expéditeur non migré : transaction rejetée");
        }
        EtlContext.Ref beneficiary =
                t.id("beneficiare_id") == null
                        ? null
                        : ctx.beneficiaryPartyByLegacyId.get(t.id("beneficiare_id"));
        if (beneficiary == null) {
            return reject(t, "Bénéficiaire absent ou non migré : transaction rejetée");
        }
        BigDecimal amount = money(t.decimal("montant"));
        BigDecimal fee =
                t.decimal("fraisenvoi") == null ? BigDecimal.ZERO : money(t.decimal("fraisenvoi"));
        if (amount == null || amount.signum() <= 0 || fee == null || fee.signum() < 0) {
            return reject(t, "Montant ou frais invalide : transaction rejetée");
        }
        BigDecimal total = t.decimal("montanttotal");
        if (total != null
                && total.subtract(amount.add(fee)).abs().compareTo(new BigDecimal("0.01")) > 0) {
            // montanttotal est souvent dans une autre devise (montant converti) : non repris,
            // simplement compté.
            ctx.report.count("transfers.total_differs");
        }
        String country =
                t.id("country_id") == null ? null : ctx.countryByLegacyId.get(t.id("country_id"));
        if (country == null) {
            return reject(t, "Pays de destination absent ou inconnu : transaction rejetée");
        }
        String destCurrency = ctx.currencyByCountry.get(country);

        Row contact = contacts.get(t.id("beneficiare_id"));
        String kind = Parse.transferKind(t.str("typetransaction"));
        String account =
                t.str("bank_account_number") != null
                        ? t.str("bank_account_number")
                        : t.str("bank_iban");
        if (kind == null) {
            kind =
                    account != null
                            ? "BANK_TRANSFER"
                            : t.str("operateur") != null ? "MOBILE_MONEY" : null;
            if (kind == null) {
                return reject(
                        t,
                        "Type de transaction « "
                                + t.str("typetransaction")
                                + " » inexploitable et non déductible : transaction rejetée");
            }
            ctx.report.warn(
                    "transaction",
                    legacyId,
                    "Type « " + t.str("typetransaction") + " » déduit : " + kind,
                    t.forReport());
        }
        String beneficiaryName = beneficiaryName(beneficiary.id());
        long destinationId;
        if ("BANK_TRANSFER".equals(kind)) {
            if (account == null && contact != null) {
                account =
                        contact.str("bankaccountnumber") != null
                                ? contact.str("bankaccountnumber")
                                : contact.str("bankiban");
            }
            if (account == null) {
                account = "N/A";
                ctx.report.warn(
                        "transaction",
                        legacyId,
                        "Aucun numéro de compte pour ce virement : destination « N/A » créée",
                        t.forReport());
            }
            String bankName = contact == null ? null : contact.str("bankname");
            destinationId =
                    destinations.bank(
                            beneficiary.id(),
                            beneficiaryName,
                            country,
                            account,
                            t.str("bank_id"),
                            bankName,
                            t.str("bank_swift"),
                            null,
                            null,
                            true);
        } else {
            String msisdn = contact == null ? null : contact.str("phone");
            if (msisdn == null) {
                msisdn = "N/A";
                ctx.report.warn(
                        "transaction",
                        legacyId,
                        "Aucun numéro mobile pour ce transfert : destination « N/A » créée",
                        t.forReport());
            }
            destinationId =
                    destinations.mobile(
                            beneficiary.id(),
                            beneficiaryName,
                            country,
                            msisdn,
                            t.str("operateur"),
                            true);
        }
        String pair = pairOf(t, sender.id(), beneficiary.id());

        Status status = status(t.str("status"));
        if (status.unresolved()) {
            ctx.report.count("transfers.unresolved");
        }
        if (status.unknown()) {
            ctx.report.warn(
                    "transaction",
                    legacyId,
                    "Statut inconnu « "
                            + t.str("status")
                            + " » : transaction annulée pour examen manuel",
                    t.forReport());
        }
        Instant created =
                t.instant("datetransaction") == null
                        ? ctx.clock.instant()
                        : t.instant("datetransaction");
        Instant completed = status.name().equals("COMPLETED") ? created : null;
        String reference = t.str("numerotransaction");
        if (reference == null || !references.add(reference)) {
            String fallback = "LEGACY-" + legacyId;
            ctx.report.warn(
                    "transaction",
                    legacyId,
                    "Référence absente ou en double (« "
                            + reference
                            + " ») : "
                            + fallback
                            + " attribuée",
                    t.forReport());
            reference = fallback;
            references.add(reference);
        }
        String providerReference = t.str("partenairenumero");
        Map<String, Object> attrs = new LinkedHashMap<>();
        for (String k :
                new String[] {
                    "modetransfert",
                    "branche",
                    "typeservice",
                    "prestateurservice",
                    "agent",
                    "partner_id",
                    "bank_swift",
                    "fileimagename",
                    "raisontransaction",
                    "origin_fonds",
                    "relaction",
                    "operateur",
                    "town_id",
                    "typetransaction",
                    "numeroidentifiant"
                }) {
            if (t.str(k) != null) {
                attrs.put(k, t.str(k));
            }
        }
        if (providerReference != null && !providerReferences.add(providerReference)) {
            attrs.put("partenairenumero_duplicate", providerReference);
            ctx.report.warn(
                    "transaction",
                    legacyId,
                    "Référence fournisseur en double (« "
                            + providerReference
                            + " ») : non reprise (conservée dans legacy_attributes)",
                    t.forReport());
            providerReference = null;
        }
        Long createdBy = holderUser.getOrDefault(holder.id(), ctx.migrationUserId);
        UUID publicId = UUID.randomUUID();
        String snapshot = snapshot(sender.id(), beneficiary.id(), destinationId);
        String finalProviderReference = providerReference;
        String finalReference = reference;
        String finalKind = kind;
        long[] id = new long[1];
        boolean ok =
                ctx.attempt(
                        () ->
                                id[0] =
                                        ctx.target
                                                .sql(
                                                        """
                                                        INSERT INTO transfers (public_id, reference, customer_id, sender_party_id, beneficiary_party_id, payout_destination_id,
                                                            kind, funding_source, party_type_pair, source_amount, fee_amount, source_currency, destination_currency,
                                                            purpose_code, source_of_funds_code, relationship_code, comment, status, failure_reason, provider,
                                                            provider_reference, snapshot, created_by, completed_at, hidden_at, legacy_id, legacy_number,
                                                            legacy_status, legacy_attributes, created_at, updated_at)
                                                        VALUES (:pid, :ref, :customer, :sender, :beneficiary, :dest, :kind, 'OFFLINE_RECEIPT', :pair, :amount, :fee, :srcCur,
                                                            :destCur, 'LEGACY', 'LEGACY', 'LEGACY', :comment, :status, :reason, 'WACE', :providerRef,
                                                            CAST(:snapshot AS jsonb), :by, :completed, CASE WHEN :hidden THEN now() END, :legacy, :number,
                                                            :legacyStatus, CAST(:attrs AS jsonb), :created, :created)
                                                        ON CONFLICT (legacy_id) DO UPDATE SET legacy_status = EXCLUDED.legacy_status
                                                        RETURNING id
                                                        """)
                                                .param("pid", publicId)
                                                .param("ref", finalReference)
                                                .param("customer", holder.id())
                                                .param("sender", sender.id())
                                                .param("beneficiary", beneficiary.id())
                                                .param("dest", destinationId)
                                                .param("kind", finalKind)
                                                .param("pair", pair)
                                                .param("amount", amount)
                                                .param("fee", fee)
                                                .param("srcCur", SOURCE_CURRENCY)
                                                .param("destCur", destCurrency)
                                                .param("comment", Parse.clean(t.str("comment")))
                                                .param("status", status.name())
                                                .param("reason", status.reason())
                                                .param("providerRef", finalProviderReference)
                                                .param("snapshot", snapshot)
                                                .param("by", createdBy)
                                                .param(
                                                        "completed",
                                                        completed == null
                                                                ? null
                                                                : Timestamp.from(completed))
                                                .param("hidden", t.bool("isdelete"))
                                                .param("legacy", legacyId)
                                                .param("number", t.str("numeroidentifiant"))
                                                .param("legacyStatus", t.str("status"))
                                                .param("attrs", JSON.writeValueAsString(attrs))
                                                .param("created", Timestamp.from(created))
                                                .query(Long.class)
                                                .single());
        if (!ok) {
            return reject(t, "Transfert refusé par le schéma : " + ctx.lastError);
        }
        ctx.target
                .sql(
                        """
                        INSERT INTO transfer_status_history (transfer_id, from_status, to_status, reason, actor_type, occurred_at)
                        VALUES (:t, NULL, :status, :reason, 'SYSTEM', :at)
                        """)
                .param("t", id[0])
                .param("status", status.name())
                .param(
                        "reason",
                        "Migration depuis l'ancien système (statut d'origine : "
                                + t.str("status")
                                + ")")
                .param("at", Timestamp.from(created))
                .update();
        ctx.report.count("transfers.status." + status.name().toLowerCase(Locale.ROOT));
        return true;
    }

    record Status(String name, String reason, boolean unresolved, boolean unknown) {}

    static Status status(String raw) {
        String s = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "effectue", "effectué", "paid", "success" ->
                    new Status("COMPLETED", null, false, false);
            case "validation", "processing", "locked" ->
                    new Status("PROCESSING", null, false, false);
            case "echoue", "échoué", "failed", "canceled", "cancelled" ->
                    new Status(
                            "FAILED",
                            "Statut historique : " + s + " (ancien système)",
                            false,
                            false);
            case "en validation", "en attente" ->
                    new Status(
                            "CANCELLED",
                            "Statut historique non résolu : « "
                                    + s
                                    + " » (client débité, issue inconnue)",
                            true,
                            false);
            default ->
                    new Status(
                            "CANCELLED", "Statut historique inconnu : « " + s + " »", true, true);
        };
    }

    private String pairOf(Row t, long senderId, long beneficiaryId) {
        String declared =
                t.str("business_type") == null
                        ? null
                        : t.str("business_type").toUpperCase(Locale.ROOT);
        if (declared != null && PAIRS.contains(declared)) {
            return declared;
        }
        String derived =
                ("BUSINESS".equals(partyKind.get(senderId)) ? "B" : "P")
                        + "2"
                        + ("BUSINESS".equals(partyKind.get(beneficiaryId)) ? "B" : "P");
        if (declared != null) {
            ctx.report.warn(
                    "transaction",
                    t.idOrZero(),
                    "business_type « "
                            + declared
                            + " » inconnu : "
                            + derived
                            + " déduit des parties",
                    t.forReport());
        }
        return derived;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private String beneficiaryName(long partyId) {
        return ctx.target
                .sql(
                        "SELECT coalesce(business_name, trim(coalesce(first_name,'') || ' ' ||"
                                + " coalesce(last_name,''))) FROM parties WHERE id = :id")
                .param("id", partyId)
                .query(String.class)
                .single();
    }

    // ------------------------------------------------------------------ instantané

    private String snapshot(long senderId, long beneficiaryId, long destinationId) {
        ObjectNode root = JSON.createObjectNode();
        root.set("sender", partyNode(senderId));
        root.set("beneficiary", partyNode(beneficiaryId));
        root.set("destination", destinationNode(destinationId));
        root.put("migrated", true);
        return JSON.writeValueAsString(root);
    }

    private ObjectNode partyNode(long id) {
        return partyNodes.computeIfAbsent(
                id,
                k ->
                        ctx.target
                                .sql(
                                        """
                                        SELECT public_id, role, kind, first_name, last_name, business_name, business_type, business_registered_on, birth_date,
                                               nationality, occupation, gender, civility, address_line, city, country_code, phone_e164, email,
                                               id_document_type, id_document_last4, id_document_expires_on, id_document_number_enc IS NOT NULL AS has_id
                                        FROM parties WHERE id = :id
                                        """)
                                .param("id", id)
                                .query(
                                        (rs, n) -> {
                                            ObjectNode node = JSON.createObjectNode();
                                            node.put("id", rs.getString("public_id"));
                                            node.put("role", rs.getString("role"));
                                            node.put("kind", rs.getString("kind"));
                                            put(node, "firstName", rs.getString("first_name"));
                                            put(node, "lastName", rs.getString("last_name"));
                                            put(
                                                    node,
                                                    "businessName",
                                                    rs.getString("business_name"));
                                            put(
                                                    node,
                                                    "businessType",
                                                    rs.getString("business_type"));
                                            put(
                                                    node,
                                                    "businessRegisteredOn",
                                                    rs.getString("business_registered_on"));
                                            put(node, "birthDate", rs.getString("birth_date"));
                                            put(node, "nationality", rs.getString("nationality"));
                                            put(node, "occupation", rs.getString("occupation"));
                                            put(node, "gender", rs.getString("gender"));
                                            put(node, "civility", rs.getString("civility"));
                                            put(node, "addressLine", rs.getString("address_line"));
                                            put(node, "city", rs.getString("city"));
                                            put(node, "countryCode", rs.getString("country_code"));
                                            put(node, "phone", rs.getString("phone_e164"));
                                            put(node, "email", rs.getString("email"));
                                            ObjectNode doc = JSON.createObjectNode();
                                            put(doc, "type", rs.getString("id_document_type"));
                                            if (rs.getString("id_document_last4") != null) {
                                                doc.put(
                                                        "numberMasked",
                                                        "****" + rs.getString("id_document_last4"));
                                            } else if (rs.getBoolean("has_id")) {
                                                doc.put("numberMasked", "****");
                                            }
                                            put(
                                                    doc,
                                                    "expiresOn",
                                                    rs.getString("id_document_expires_on"));
                                            if (!doc.isEmpty()) {
                                                node.set("idDocument", doc);
                                            }
                                            return node;
                                        })
                                .single());
    }

    private ObjectNode destinationNode(long id) {
        return ctx.target
                .sql(
                        """
                        SELECT d.public_id, d.kind, d.country_code, d.account_holder_name, d.account_number_masked, d.swift_code, d.ifsc_code,
                               d.routing_number, b.id AS bank_id, b.name AS bank_name, o.id AS op_id, o.name AS op_name
                        FROM payout_destinations d LEFT JOIN banks b ON b.id = d.bank_id LEFT JOIN mobile_operators o ON o.id = d.mobile_operator_id
                        WHERE d.id = :id
                        """)
                .param("id", id)
                .query(
                        (rs, n) -> {
                            ObjectNode node = JSON.createObjectNode();
                            node.put("id", rs.getString("public_id"));
                            node.put("kind", rs.getString("kind"));
                            node.put("countryCode", rs.getString("country_code"));
                            if (rs.getString("bank_name") != null) {
                                node.putObject("bank")
                                        .put("id", rs.getLong("bank_id"))
                                        .put("name", rs.getString("bank_name"));
                            }
                            if (rs.getString("op_name") != null) {
                                node.putObject("mobileOperator")
                                        .put("id", rs.getLong("op_id"))
                                        .put("name", rs.getString("op_name"));
                            }
                            put(node, "accountHolderName", rs.getString("account_holder_name"));
                            node.put("accountNumberMasked", rs.getString("account_number_masked"));
                            put(node, "swiftCode", rs.getString("swift_code"));
                            put(node, "ifscCode", rs.getString("ifsc_code"));
                            put(node, "routingNumber", rs.getString("routing_number"));
                            return node;
                        })
                .single();
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
