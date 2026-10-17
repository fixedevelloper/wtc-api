package com.wtc.payment.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Port du fournisseur de paiement (Wace Pay). Le domaine ne connaît que ce contrat ; l'adaptateur
 * réel et le faux sont interchangeables.
 *
 * <p>Chaque appel renvoie un {@link Result} qui <b>classe</b> l'issue, car c'est elle qui décide de
 * ce qu'on a le droit de faire ensuite :
 *
 * <ul>
 *   <li>{@code OK} : fait ;
 *   <li>{@code REJECTED} : refus explicite du fournisseur, rien n'a été créé (on peut rembourser) ;
 *   <li>{@code NOT_PROCESSED} : l'appel n'a certainement pas atteint le fournisseur (connexion
 *       refusée, authentification expirée) : on peut le refaire ;
 *   <li>{@code AMBIGUOUS} : on ne sait pas si l'ordre existe (délai dépassé, réponse illisible,
 *       erreur système) : on ne le refait <b>jamais</b> et on ne rembourse pas, un humain tranche.
 * </ul>
 */
public interface PayoutGateway {

    enum Outcome {
        OK,
        REJECTED,
        NOT_PROCESSED,
        AMBIGUOUS
    }

    /**
     * @param code code du fournisseur (ex. « 2011 »), nul si sans objet
     * @param requestAudit requête telle qu'envoyée, <b>numéros masqués</b> (conservée dans {@code
     *     payout_attempts})
     * @param responseAudit réponse reçue, masquée
     */
    record Result<T>(
            Outcome outcome,
            T value,
            String code,
            String message,
            String requestAudit,
            String responseAudit) {

        public static <T> Result<T> ok(T value, String requestAudit, String responseAudit) {
            return new Result<>(Outcome.OK, value, null, null, requestAudit, responseAudit);
        }

        public static <T> Result<T> failure(
                Outcome outcome,
                String code,
                String message,
                String requestAudit,
                String responseAudit) {
            return new Result<>(outcome, null, code, message, requestAudit, responseAudit);
        }

        public boolean isOk() {
            return outcome == Outcome.OK;
        }
    }

    /**
     * Expéditeur à déclarer chez le fournisseur. {@code type} : P (particulier) ou B (entreprise).
     */
    record SenderData(
            String type,
            String firstName,
            String lastName,
            String businessName,
            String businessType,
            String address,
            String phone,
            String email,
            String country,
            String city,
            String gender,
            String civility,
            String idNumber,
            String idType,
            String occupation,
            String nationality,
            LocalDate dateOfBirth,
            LocalDate businessRegisteredOn,
            LocalDate idExpiresOn,
            boolean pep) {}

    record BeneficiaryData(
            String type,
            String firstName,
            String lastName,
            String businessName,
            String businessType,
            String address,
            String phone,
            String email,
            String country,
            String city,
            String idNumber,
            String idType,
            LocalDate dateOfBirth,
            LocalDate idExpiresOn) {}

    /**
     * Ordre d'envoi. Selon {@code kind}, seuls les champs mobiles ou bancaires correspondants sont
     * utilisés.
     */
    record PayoutOrder(
            Kind kind,
            String payerCode,
            BigDecimal amountToPay,
            String fromCurrency,
            String fromCountry,
            String senderCode,
            String beneficiaryCode,
            String originFund,
            String reason,
            String relation,
            String mobileNumber,
            String bankAccount,
            String bankName,
            Long bankId,
            String bankBranch,
            String bankSwiftCode,
            String comment) {

        public enum Kind {
            BANK,
            WALLET
        }
    }

    /** Ordre créé chez le fournisseur : sa référence et les montants qu'il annonce. */
    record OrderCreated(
            String reference,
            BigDecimal amountReceived,
            BigDecimal providerFee,
            BigDecimal totalCharged) {}

    enum ProviderStatus {
        PENDING,
        WAITING_CONFIRMATION,
        PROCESSING,
        PAID,
        CANCEL,
        LOCKED,
        UNKNOWN
    }

    record OrderStatus(
            ProviderStatus status,
            String rawStatus,
            BigDecimal amountToReceive,
            BigDecimal providerFee) {}

    record PartnerBalance(String agency, BigDecimal balance, String currency) {}

    Result<String> createSender(SenderData data);

    Result<String> createBeneficiary(String senderCode, BeneficiaryData data);

    /** Crée l'ordre. <b>Jamais rejoué automatiquement</b> après {@code AMBIGUOUS}. */
    Result<OrderCreated> createOrder(PayoutOrder order);

    /** Confirme un ordre créé. Sans danger à rejouer (déjà confirmé = succès). */
    Result<Boolean> confirm(String reference);

    Result<OrderStatus> status(String reference);

    Result<PartnerBalance> balance();

    // ------------------------------------------------------------------ référentiels du
    // fournisseur

    /**
     * Service de paiement d'un pays (banque, portefeuille mobile…) : {@code B} = dépôt bancaire,
     * {@code WV} = Wave, etc.
     */
    record PayoutService(String code, String name, String currency) {}

    /** Banque connue du fournisseur : identifiant numérique, code de banque et nom. */
    record ProviderBank(long id, String code, String name) {}

    /** Services disponibles dans un pays pour une devise de paiement. */
    Result<java.util.List<PayoutService>> payoutServices(String country, String currency);

    /** Code « payeur » d'un service dans un pays : exigé pour créer un ordre. */
    Result<String> payerCode(String country, String service, String currency);

    Result<java.util.List<ProviderBank>> banks(String country, String payerCode);

    /**
     * Listes {@code {clé: libellé}} : liens entre expéditeur et bénéficiaire, origines des fonds,
     * motifs (par type P2P, P2B, B2P, B2B).
     */
    Result<java.util.Map<String, String>> relations();

    Result<java.util.Map<String, String>> originFunds(String businessType);

    Result<java.util.Map<String, String>> reasons(String businessType);
}
