package com.wtc.transfer.application;

import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.outbox.EventPublisher;
import com.wtc.transfer.application.port.TransferRepository;
import com.wtc.transfer.domain.TransferStatus;
import com.wtc.wallet.application.AccountRef;
import com.wtc.wallet.application.LedgerKind;
import com.wtc.wallet.application.LedgerService;
import com.wtc.wallet.application.PostingLeg;
import com.wtc.wallet.application.PostingRequest;
import com.wtc.wallet.application.PostingResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Seul endroit qui change l'état d'un transfert et qui rend l'argent. Les appelants tiennent le
 * verrou de la ligne ({@code TransferRepository.lock}) dans leur transaction.
 */
@Component
class TransferLifecycle {

    static final String CLEARING = "PLATFORM_PAYOUT_CLEARING";
    static final String FEES = "PLATFORM_FEE_REVENUE";

    private final TransferRepository transfers;
    private final LedgerService ledger;
    private final EventPublisher events;
    private final tools.jackson.databind.json.JsonMapper json;

    TransferLifecycle(
            TransferRepository transfers,
            LedgerService ledger,
            EventPublisher events,
            tools.jackson.databind.json.JsonMapper json) {
        this.transfers = transfers;
        this.ledger = ledger;
        this.events = events;
        this.json = json;
    }

    /**
     * Annonce un changement visible du transfert (notifications). Le contenu est celui que le
     * client voit déjà : montants, référence, nom du bénéficiaire ; jamais de numéro de compte ni
     * de pièce.
     */
    void announce(TransferRow t, String eventType) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("transferId", t.publicId().toString());
        payload.put("reference", t.reference());
        payload.put("customerId", t.customerPublicId().toString());
        payload.put("sourceAmount", t.sourceAmount().stripTrailingZeros().toPlainString());
        payload.put("fee", t.feeAmount().stripTrailingZeros().toPlainString());
        payload.put("totalDebit", t.totalDebit().stripTrailingZeros().toPlainString());
        payload.put("sourceCurrency", t.sourceCurrency());
        if (t.destinationAmount() != null) {
            payload.put(
                    "destinationAmount",
                    t.destinationAmount().stripTrailingZeros().toPlainString());
            payload.put("destinationCurrency", t.destinationCurrency());
        }
        var beneficiary = json.readTree(t.snapshotJson()).path("beneficiary");
        String name =
                "BUSINESS".equals(beneficiary.path("kind").asString())
                        ? beneficiary.path("businessName").asString("")
                        : (beneficiary.path("firstName").asString("")
                                        + " "
                                        + beneficiary.path("lastName").asString(""))
                                .trim();
        payload.put("beneficiaryName", name);
        events.publish("TRANSFER", t.id(), eventType, payload);
    }

    /**
     * Change l'état si la transition est permise et si personne ne l'a déjà changé ; trace
     * l'historique.
     */
    void move(
            TransferRow t,
            TransferStatus to,
            String reason,
            String actorType,
            UUID actor,
            String metadataJson) {
        if (!t.status().canMoveTo(to)) {
            throw new IllegalStateException(
                    "Transition interdite "
                            + t.status()
                            + " → "
                            + to
                            + " (transfert "
                            + t.reference()
                            + ")");
        }
        if (!transfers.transition(
                t.id(), t.status(), to, to == TransferStatus.FAILED ? reason : null)) {
            throw new DomainException(
                    ErrorCode.CONFLICT, "Le transfert a été modifié entre-temps.");
        }
        transfers.appendHistory(t.id(), t.status(), to, reason, actorType, actor, metadataJson);
        if (to == TransferStatus.COMPLETED) {
            announce(transfers.findById(t.id()).orElseThrow(), "transfer.completed");
        }
    }

    /**
     * Débit à la création : le client paie montant + frais ; le montant va en transit vers le
     * fournisseur, les frais en produit.
     */
    PostingResult debit(TransferRow t, UUID createdBy) {
        List<PostingLeg> legs = new ArrayList<>();
        legs.add(
                PostingLeg.debit(
                        AccountRef.wallet(t.walletId()), t.totalDebit(), t.sourceCurrency()));
        legs.add(
                PostingLeg.credit(
                        AccountRef.platform(CLEARING), t.sourceAmount(), t.sourceCurrency()));
        if (t.feeAmount().signum() > 0) {
            legs.add(
                    PostingLeg.credit(
                            AccountRef.platform(FEES), t.feeAmount(), t.sourceCurrency()));
        }
        return ledger.post(
                new PostingRequest(
                        debitReference(t.publicId()),
                        LedgerKind.TRANSFER_DEBIT,
                        "Transfert " + t.reference(),
                        legs,
                        createdBy,
                        "TRANSFER",
                        t.id()));
    }

    static String debitReference(UUID transferId) {
        return "transfer:" + transferId + ":debit";
    }

    static String refundReference(UUID transferId) {
        return "transfer:" + transferId + ":refund";
    }

    /**
     * Rembourse un transfert <b>échoué</b>, une seule fois : la référence du grand livre est unique
     * et le passage FAILED → REFUNDED est conditionnel. Un second appel ne fait rien.
     */
    TransferRow refund(TransferRow t, String actorType, UUID actor, String reason) {
        if (t.status() == TransferStatus.REFUNDED) {
            return t;
        }
        if (t.status() != TransferStatus.FAILED) {
            throw new DomainException(
                    ErrorCode.TRANSFER_NOT_REFUNDABLE,
                    "Seul un transfert échoué se rembourse (état actuel : " + t.status() + ").");
        }
        List<PostingLeg> legs = new ArrayList<>();
        legs.add(
                PostingLeg.credit(
                        AccountRef.wallet(t.walletId()), t.totalDebit(), t.sourceCurrency()));
        legs.add(
                PostingLeg.debit(
                        AccountRef.platform(CLEARING), t.sourceAmount(), t.sourceCurrency()));
        if (t.feeAmount().compareTo(BigDecimal.ZERO) > 0) {
            legs.add(
                    PostingLeg.debit(AccountRef.platform(FEES), t.feeAmount(), t.sourceCurrency()));
        }
        PostingResult posted =
                ledger.post(
                        new PostingRequest(
                                refundReference(t.publicId()),
                                LedgerKind.TRANSFER_REFUND,
                                "Remboursement " + t.reference(),
                                legs,
                                actor,
                                "TRANSFER",
                                t.id()));
        if (!transfers.refundTransition(t.id(), posted.ledgerTransactionId())) {
            throw new DomainException(
                    ErrorCode.CONFLICT, "Le transfert a été modifié entre-temps.");
        }
        transfers.appendHistory(
                t.id(),
                TransferStatus.FAILED,
                TransferStatus.REFUNDED,
                reason,
                actorType,
                actor,
                null);
        TransferRow refunded = transfers.findById(t.id()).orElseThrow();
        announce(refunded, "transfer.refunded");
        return refunded;
    }
}
