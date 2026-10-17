package com.wtc.transfer.application;

import com.wtc.customer.application.PartyData;
import com.wtc.customer.application.TransferDestination;
import com.wtc.customer.application.TransferParty;
import com.wtc.reference.application.BankView;
import com.wtc.reference.application.MobileOperatorView;
import com.wtc.reference.application.PricingQuote;
import com.wtc.shared.security.SecretCipher;
import java.util.Base64;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Instantané d'un transfert : l'identité des personnes, la destination, le barème, le taux et les
 * valeurs envoyées au fournisseur, <b>figés à la création</b>. Modifier plus tard un contact ou une
 * destination ne change ni ce qui sera payé ni ce que le reçu affichera. Les numéros (pièces,
 * compte) y sont chiffrés (AES-GCM, contexte propre au transfert) ; les parties {@code sender},
 * {@code beneficiary} et {@code destination} ont déjà la forme exposée par l'API.
 */
public final class TransferSnapshot {

    /** Valeurs exactement telles qu'attendues par Wace pour cet envoi. */
    public record ProviderCodes(
            String payerCode,
            Long bankId,
            String bankCode,
            String bankName,
            String originFund,
            String reason,
            String relation) {}

    private final JsonMapper json;
    private final SecretCipher cipher;
    private final UUID transferId;
    private final JsonNode root;

    private TransferSnapshot(JsonMapper json, SecretCipher cipher, UUID transferId, JsonNode root) {
        this.json = json;
        this.cipher = cipher;
        this.transferId = transferId;
        this.root = root;
    }

    static String context(UUID transferId) {
        return "transfer-snapshot:" + transferId;
    }

    static String build(
            JsonMapper json,
            SecretCipher cipher,
            UUID transferId,
            TransferParty sender,
            TransferParty beneficiary,
            TransferDestination destination,
            BankView bank,
            MobileOperatorView operator,
            PricingQuote quote,
            ProviderCodes codes) {
        ObjectNode root = json.createObjectNode();
        root.set("sender", partyNode(json, sender));
        root.set("beneficiary", partyNode(json, beneficiary));

        ObjectNode dest = json.createObjectNode();
        dest.put("id", destination.publicId().toString());
        dest.put("kind", destination.kind());
        dest.put("countryCode", destination.countryCode());
        if (bank != null) {
            dest.set(
                    "bank",
                    json.valueToTree(java.util.Map.of("id", bank.id(), "name", bank.name())));
        }
        if (operator != null) {
            dest.set(
                    "mobileOperator",
                    json.valueToTree(
                            java.util.Map.of("id", operator.id(), "name", operator.name())));
        }
        putIf(dest, "accountHolderName", destination.accountHolderName());
        dest.put("accountNumberMasked", destination.accountNumberMasked());
        putIf(dest, "swiftCode", destination.swiftCode());
        putIf(dest, "ifscCode", destination.ifscCode());
        putIf(dest, "routingNumber", destination.routingNumber());
        root.set("destination", dest);

        ObjectNode pricing = json.createObjectNode();
        pricing.put("feePercent", quote.feePercent().stripTrailingZeros().toPlainString());
        pricing.put("feeScheduleId", quote.feeScheduleId());
        pricing.put("fxRate", quote.fxRate().stripTrailingZeros().toPlainString());
        root.set("pricing", pricing);
        root.set("provider", json.valueToTree(codes));
        root.set(
                "data",
                json.valueToTree(
                        java.util.Map.of(
                                "sender", sender.data(), "beneficiary", beneficiary.data())));

        ObjectNode secrets = json.createObjectNode();
        putIf(secrets, "senderIdNumber", seal(cipher, transferId, sender.idDocumentNumber()));
        putIf(
                secrets,
                "beneficiaryIdNumber",
                seal(cipher, transferId, beneficiary.idDocumentNumber()));
        putIf(secrets, "accountNumber", seal(cipher, transferId, destination.accountNumber()));
        root.set("secrets", secrets);
        return json.writeValueAsString(root);
    }

    public static TransferSnapshot read(
            JsonMapper json, SecretCipher cipher, UUID transferId, String snapshotJson) {
        return new TransferSnapshot(json, cipher, transferId, json.readTree(snapshotJson));
    }

    // ------------------------------------------------------------------ lecture

    public JsonNode sender() {
        return root.get("sender");
    }

    public JsonNode beneficiary() {
        return root.get("beneficiary");
    }

    public JsonNode destination() {
        return root.get("destination");
    }

    public ProviderCodes provider() {
        return json.treeToValue(root.get("provider"), ProviderCodes.class);
    }

    public PartyData senderData() {
        return json.treeToValue(root.get("data").get("sender"), PartyData.class);
    }

    public PartyData beneficiaryData() {
        return json.treeToValue(root.get("data").get("beneficiary"), PartyData.class);
    }

    public String senderIdNumber() {
        return open("senderIdNumber");
    }

    public String beneficiaryIdNumber() {
        return open("beneficiaryIdNumber");
    }

    public String accountNumber() {
        return open("accountNumber");
    }

    private String open(String field) {
        JsonNode sealed = root.get("secrets").get(field);
        return sealed == null
                ? null
                : cipher.decrypt(
                        Base64.getDecoder().decode(sealed.asString()), context(transferId));
    }

    // ------------------------------------------------------------------ construction

    private static String seal(SecretCipher cipher, UUID transferId, String value) {
        return value == null || value.isBlank()
                ? null
                : Base64.getEncoder().encodeToString(cipher.encrypt(value, context(transferId)));
    }

    private static ObjectNode partyNode(JsonMapper json, TransferParty party) {
        PartyData d = party.data();
        ObjectNode node = json.createObjectNode();
        node.put("id", party.publicId().toString());
        node.put("role", party.role());
        node.put("kind", d.kind());
        putIf(node, "firstName", d.firstName());
        putIf(node, "lastName", d.lastName());
        putIf(node, "businessName", d.businessName());
        putIf(node, "businessType", d.businessType());
        if (d.businessRegisteredOn() != null) {
            node.put("businessRegisteredOn", d.businessRegisteredOn().toString());
        }
        if (d.birthDate() != null) {
            node.put("birthDate", d.birthDate().toString());
        }
        putIf(node, "nationality", d.nationality());
        putIf(node, "occupation", d.occupation());
        putIf(node, "gender", d.gender());
        putIf(node, "civility", d.civility());
        putIf(node, "addressLine", d.addressLine());
        putIf(node, "city", d.city());
        putIf(node, "countryCode", d.countryCode());
        putIf(node, "phone", d.phone());
        putIf(node, "email", d.email());
        ObjectNode id = json.createObjectNode();
        putIf(id, "type", d.idDocumentType());
        String number = party.idDocumentNumber();
        if (number != null && !number.isBlank()) {
            id.put(
                    "numberMasked",
                    number.length() <= 4 ? "****" : "****" + number.substring(number.length() - 4));
        }
        if (d.idDocumentExpiresOn() != null) {
            id.put("expiresOn", d.idDocumentExpiresOn().toString());
        }
        if (!id.isEmpty()) {
            node.set("idDocument", id);
        }
        node.put("pep", d.pep());
        return node;
    }

    private static void putIf(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
