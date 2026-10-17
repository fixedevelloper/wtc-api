package com.wtc.transfer.application;

import com.wtc.payment.application.PayoutGateway;
import com.wtc.payment.application.PayoutGateway.OrderCreated;
import com.wtc.payment.application.PayoutGateway.OrderStatus;
import com.wtc.payment.application.PayoutGateway.Outcome;
import com.wtc.payment.application.PayoutGateway.PayoutOrder;
import com.wtc.payment.application.PayoutGateway.Result;
import com.wtc.shared.config.WtcProperties;
import com.wtc.shared.outbox.OutboxRepository;
import com.wtc.shared.outbox.OutboxRepository.Event;
import com.wtc.shared.security.SecretCipher;
import com.wtc.transfer.application.TransferSnapshot.ProviderCodes;
import com.wtc.transfer.application.port.PayoutAttemptRepository;
import com.wtc.transfer.application.port.ProviderRefRepository;
import com.wtc.transfer.application.port.TransferRepository;
import com.wtc.transfer.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Envoi des ordres au fournisseur et suivi de leur statut. Les règles qui protègent l'argent :
 *
 * <ol>
 *   <li><b>Aucun appel réseau dans une transaction de base.</b>
 *   <li><b>Une tentative est écrite avant l'appel</b>, pessimiste (« ambiguë ») : si le processus
 *       meurt pendant l'appel, la trace interdit de recréer l'ordre.
 *   <li><b>Un ordre n'est jamais recréé</b> après une réponse ambiguë ; le transfert est bloqué
 *       pour décision humaine.
 *   <li><b>Rembourser ne se fait que sur un échec confirmé</b> (refus explicite, annulation, ou
 *       ordre jamais créé après plusieurs échecs « non traité »), et une seule fois.
 *   <li>La confirmation, elle, se rejoue sans danger (spécification Wace).
 * </ol>
 */
@Component
public class PayoutProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(PayoutProcessor.class);
    static final String EVENT_CONFIRM = "payout.confirm";
    private static final String PROVIDER = "WACE";
    private static final Duration MAX_BACKOFF = Duration.ofHours(2);

    private final TransferRepository transfers;
    private final PayoutAttemptRepository attempts;
    private final OutboxRepository outbox;
    private final ProviderRefRepository refs;
    private final PayoutGateway gateway;
    private final TransferLifecycle lifecycle;
    private final SecretCipher cipher;
    private final JsonMapper json;
    private final Clock clock;
    private final WtcProperties.Worker config;
    private final TransactionTemplate tx;

    PayoutProcessor(
            TransferRepository transfers,
            PayoutAttemptRepository attempts,
            OutboxRepository outbox,
            ProviderRefRepository refs,
            PayoutGateway gateway,
            TransferLifecycle lifecycle,
            SecretCipher cipher,
            JsonMapper json,
            Clock clock,
            WtcProperties properties,
            PlatformTransactionManager txManager) {
        this.transfers = transfers;
        this.attempts = attempts;
        this.outbox = outbox;
        this.refs = refs;
        this.gateway = gateway;
        this.lifecycle = lifecycle;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
        this.config = properties.payment().worker();
        this.tx = new TransactionTemplate(txManager);
    }

    // ================================================================== boîte d'envoi

    /**
     * Traite les événements dus ; retourne leur nombre. Appelé à intervalle régulier et par les
     * tests.
     */
    public int processOutbox() {
        outbox.releaseStale(clock.instant().minus(Duration.ofMinutes(10)));
        List<Event> events =
                tx.execute(
                        s ->
                                outbox.claimDue(
                                        List.of(TransferService.EVENT_CREATE_ORDER, EVENT_CONFIRM),
                                        config.batchSize(),
                                        clock.instant()));
        if (events == null) {
            return 0;
        }
        for (Event event : events) {
            try {
                if (TransferService.EVENT_CREATE_ORDER.equals(event.type())) {
                    createOrder(event);
                } else {
                    confirmOrder(event);
                }
            } catch (RuntimeException e) {
                // Une erreur inattendue ne doit jamais faire rejouer une création à l'aveugle :
                // l'événement échoue, un humain regarde.
                LOG.error(
                        "Traitement de l'événement {} ({}) en erreur", event.id(), event.type(), e);
                outbox.fail(event.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return events.size();
    }

    // ------------------------------------------------------------------ création de l'ordre

    private record Prepared(TransferRow transfer, long attemptId, TransferSnapshot snapshot) {}

    private void createOrder(Event event) {
        Optional<Prepared> prepared = tx.execute(s -> prepareCreate(event.aggregateId()));
        if (prepared == null || prepared.isEmpty()) {
            outbox.done(event.id());
            return;
        }
        TransferRow t = prepared.get().transfer();
        TransferSnapshot snapshot = prepared.get().snapshot();
        long attemptId = prepared.get().attemptId();

        // --- hors transaction : appels au fournisseur
        Result<String> senderCode = ensureSender(t, snapshot);
        if (!senderCode.isOk()) {
            finishBeforeOrder(event, t, attemptId, senderCode);
            return;
        }
        Result<String> beneficiaryCode = ensureBeneficiary(t, snapshot, senderCode.value());
        if (!beneficiaryCode.isOk()) {
            finishBeforeOrder(event, t, attemptId, beneficiaryCode);
            return;
        }
        PayoutOrder order = order(t, snapshot, senderCode.value(), beneficiaryCode.value());
        Result<OrderCreated> result = gateway.createOrder(order);

        tx.executeWithoutResult(s -> finishCreate(event, t.id(), attemptId, result));
    }

    private Optional<Prepared> prepareCreate(long transferId) {
        TransferRow t = transfers.lock(transferId).orElse(null);
        if (t == null || t.status() != TransferStatus.PAYOUT_PENDING || t.payoutHold() != null) {
            return Optional.empty();
        }
        var previous = attempts.list(t.id());
        if (previous.stream().anyMatch(a -> "AMBIGUOUS".equals(a.outcome()))) {
            // Une tentative a peut-être créé l'ordre : on ne recrée jamais, un humain tranche.
            transfers.setHold(t.id(), "AMBIGUOUS_CREATE");
            transfers.appendHistory(
                    t.id(),
                    t.status(),
                    t.status(),
                    "Tentative précédente d'issue incertaine : envoi bloqué pour vérification",
                    "SYSTEM",
                    null,
                    null);
            return Optional.empty();
        }
        long attemptId = attempts.start(t.id(), previous.size() + 1);
        return Optional.of(
                new Prepared(
                        t,
                        attemptId,
                        TransferSnapshot.read(json, cipher, t.publicId(), t.snapshotJson())));
    }

    private Result<String> ensureSender(TransferRow t, TransferSnapshot s) {
        Optional<String> known = tx.execute(x -> refs.find(t.senderPartyId(), PROVIDER, null));
        if (known != null && known.isPresent()) {
            return Result.ok(known.get(), "cached", "cached");
        }
        var d = s.senderData();
        boolean business = "BUSINESS".equals(d.kind());
        Result<String> created =
                gateway.createSender(
                        new PayoutGateway.SenderData(
                                business ? "B" : "P",
                                d.firstName(),
                                d.lastName(),
                                d.businessName(),
                                d.businessType(),
                                d.addressLine(),
                                d.phone(),
                                d.email(),
                                d.countryCode(),
                                d.city(),
                                d.gender(),
                                WaceRules.civility(d.civility()),
                                s.senderIdNumber(),
                                WaceRules.idType(d.idDocumentType()),
                                d.occupation(),
                                d.nationality(),
                                d.birthDate(),
                                d.businessRegisteredOn(),
                                d.idDocumentExpiresOn(),
                                d.pep()));
        if (created.isOk()) {
            tx.executeWithoutResult(
                    x -> refs.save(t.senderPartyId(), PROVIDER, null, created.value()));
        }
        return created;
    }

    private Result<String> ensureBeneficiary(TransferRow t, TransferSnapshot s, String senderCode) {
        Optional<String> known =
                tx.execute(x -> refs.find(t.beneficiaryPartyId(), PROVIDER, t.senderPartyId()));
        if (known != null && known.isPresent()) {
            return Result.ok(known.get(), "cached", "cached");
        }
        var d = s.beneficiaryData();
        boolean business = "BUSINESS".equals(d.kind());
        Result<String> created =
                gateway.createBeneficiary(
                        senderCode,
                        new PayoutGateway.BeneficiaryData(
                                business ? "B" : "P",
                                d.firstName(),
                                d.lastName(),
                                d.businessName(),
                                d.businessType(),
                                d.addressLine(),
                                d.phone(),
                                d.email(),
                                d.countryCode(),
                                d.city(),
                                s.beneficiaryIdNumber(),
                                WaceRules.idType(d.idDocumentType()),
                                d.birthDate(),
                                d.idDocumentExpiresOn()));
        if (created.isOk()) {
            tx.executeWithoutResult(
                    x ->
                            refs.save(
                                    t.beneficiaryPartyId(),
                                    PROVIDER,
                                    t.senderPartyId(),
                                    created.value()));
        }
        return created;
    }

    private PayoutOrder order(
            TransferRow t, TransferSnapshot s, String senderCode, String beneficiaryCode) {
        ProviderCodes p = s.provider();
        boolean bank = "BANK_TRANSFER".equals(t.kind());
        String number = s.accountNumber();
        var destination = s.destination();
        return new PayoutOrder(
                bank ? PayoutOrder.Kind.BANK : PayoutOrder.Kind.WALLET,
                p.payerCode(),
                t.sourceAmount(),
                t.sourceCurrency(),
                s.senderData().countryCode(),
                senderCode,
                beneficiaryCode,
                p.originFund(),
                p.reason(),
                p.relation(),
                bank ? null : number,
                bank ? number : null,
                p.bankName(),
                p.bankId(),
                bank ? p.bankCode() : null,
                bank
                        ? (destination.has("swiftCode")
                                ? destination.get("swiftCode").asString()
                                : p.bankCode())
                        : null,
                bank
                        ? t.reference()
                        : null); // la référence du transfert sur le reçu bancaire : le seul moyen
        // de retrouver un ordre
    }

    /**
     * Échec d'une étape préalable (création de l'expéditeur ou du bénéficiaire) : aucun ordre
     * n'existe encore.
     */
    private void finishBeforeOrder(
            Event event, TransferRow t, long attemptId, Result<String> failure) {
        tx.executeWithoutResult(
                s -> {
                    TransferRow locked = transfers.lock(t.id()).orElseThrow();
                    attempts.finish(
                            attemptId,
                            failure.outcome() == Outcome.REJECTED ? "REJECTED" : "ERROR",
                            failure.requestAudit(),
                            failure.responseAudit(),
                            failure.code(),
                            null);
                    if (failure.outcome() == Outcome.REJECTED) {
                        failAndRefund(
                                locked,
                                "Refus du fournisseur à la déclaration des personnes ("
                                        + failure.code()
                                        + ") : "
                                        + failure.message());
                        outbox.done(event.id());
                    } else {
                        retryOrGiveUp(event, locked, failure.message());
                    }
                });
    }

    private void finishCreate(
            Event event, long transferId, long attemptId, Result<OrderCreated> result) {
        TransferRow t = transfers.lock(transferId).orElseThrow();
        switch (result.outcome()) {
            case OK -> {
                OrderCreated created = result.value();
                attempts.finish(
                        attemptId,
                        "ACCEPTED",
                        result.requestAudit(),
                        result.responseAudit(),
                        "CREATED",
                        created.reference());
                transfers.markProviderOrder(
                        t.id(),
                        created.reference(),
                        "WAITING_CONFIRMATION",
                        created.providerFee(),
                        created.amountReceived());
                lifecycle.move(
                        t,
                        TransferStatus.PROCESSING,
                        "Ordre créé chez le fournisseur",
                        "PROVIDER",
                        null,
                        "{\"providerReference\":\"" + created.reference() + "\"}");
                if (amountDiffers(t, created.amountReceived())) {
                    transfers.setHold(t.id(), "AMOUNT_MISMATCH");
                    transfers.appendHistory(
                            t.id(),
                            TransferStatus.PROCESSING,
                            TransferStatus.PROCESSING,
                            "Montant annoncé par le fournisseur ("
                                    + created.amountReceived()
                                    + ") différent du devis ("
                                    + t.destinationAmount()
                                    + ") : ordre non confirmé",
                            "SYSTEM",
                            null,
                            null);
                } else {
                    outbox.enqueue(
                            TransferService.OUTBOX_AGGREGATE,
                            t.id(),
                            EVENT_CONFIRM,
                            clock.instant());
                }
                outbox.done(event.id());
            }
            case REJECTED -> {
                attempts.finish(
                        attemptId,
                        "REJECTED",
                        result.requestAudit(),
                        result.responseAudit(),
                        result.code(),
                        null);
                failAndRefund(
                        t, "Refus du fournisseur (" + result.code() + ") : " + result.message());
                outbox.done(event.id());
            }
            case NOT_PROCESSED -> {
                attempts.finish(
                        attemptId,
                        "ERROR",
                        result.requestAudit(),
                        result.responseAudit(),
                        result.code(),
                        null);
                retryOrGiveUp(event, t, result.message());
            }
            case AMBIGUOUS -> {
                attempts.finish(
                        attemptId,
                        "AMBIGUOUS",
                        result.requestAudit(),
                        result.responseAudit(),
                        result.code(),
                        null);
                transfers.setHold(t.id(), "AMBIGUOUS_CREATE");
                transfers.appendHistory(
                        t.id(),
                        t.status(),
                        t.status(),
                        "Réponse incertaine du fournisseur : envoi bloqué, aucune nouvelle"
                                + " tentative ni remboursement automatique",
                        "SYSTEM",
                        null,
                        null);
                outbox.done(event.id());
            }
        }
    }

    /**
     * Ordre jamais créé : on réessaie avec un délai croissant, puis on échoue et on rembourse (rien
     * n'existe chez le fournisseur).
     */
    private void retryOrGiveUp(Event event, TransferRow t, String error) {
        if (event.attempts() >= config.maxNotProcessedAttempts()) {
            failAndRefund(
                    t,
                    "Fournisseur indisponible après "
                            + event.attempts()
                            + " tentatives, ordre jamais créé : "
                            + error);
            outbox.done(event.id());
        } else {
            outbox.retryLater(event.id(), clock.instant().plus(backoff(event.attempts())), error);
        }
    }

    private void failAndRefund(TransferRow t, String reason) {
        lifecycle.move(t, TransferStatus.FAILED, reason, "SYSTEM", null, null);
        lifecycle.refund(
                transfers.findById(t.id()).orElseThrow(),
                "SYSTEM",
                null,
                "Remboursement automatique : " + reason);
    }

    private boolean amountDiffers(TransferRow t, BigDecimal announced) {
        return announced != null
                && t.destinationAmount() != null
                && announced.compareTo(t.destinationAmount()) != 0;
    }

    // ------------------------------------------------------------------ confirmation

    private void confirmOrder(Event event) {
        Optional<TransferRow> candidate =
                tx.execute(
                        s ->
                                transfers
                                        .findById(event.aggregateId())
                                        .filter(
                                                t ->
                                                        t.status() == TransferStatus.PROCESSING
                                                                && t.providerReference() != null
                                                                && t.providerConfirmedAt() == null
                                                                && t.payoutHold() == null));
        if (candidate == null || candidate.isEmpty()) {
            outbox.done(event.id());
            return;
        }
        TransferRow t = candidate.get();
        Result<Boolean> result = gateway.confirm(t.providerReference()); // sans danger à rejouer
        tx.executeWithoutResult(
                s -> {
                    TransferRow locked = transfers.lock(t.id()).orElseThrow();
                    if (result.isOk()) {
                        transfers.markConfirmed(locked.id());
                        transfers.appendHistory(
                                locked.id(),
                                locked.status(),
                                locked.status(),
                                "Ordre confirmé chez le fournisseur",
                                "PROVIDER",
                                null,
                                null);
                        outbox.done(event.id());
                    } else if (result.outcome() == Outcome.REJECTED
                            || event.attempts() >= config.maxConfirmAttempts()) {
                        transfers.setHold(locked.id(), "CONFIRM_FAILED");
                        transfers.appendHistory(
                                locked.id(),
                                locked.status(),
                                locked.status(),
                                "Confirmation impossible ("
                                        + result.code()
                                        + ") : "
                                        + result.message(),
                                "SYSTEM",
                                null,
                                null);
                        outbox.fail(event.id(), String.valueOf(result.message()));
                    } else {
                        outbox.retryLater(
                                event.id(),
                                clock.instant().plus(backoff(event.attempts())),
                                String.valueOf(result.message()));
                    }
                });
    }

    // ================================================================== suivi des statuts

    /** Interroge les transferts en cours chez le fournisseur ; retourne le nombre interrogés. */
    public int pollStatuses() {
        List<TransferRow> due =
                tx.execute(
                        s ->
                                transfers.dueForPolling(
                                        clock.instant().minus(config.statusPollInterval()),
                                        config.batchSize()));
        if (due == null) {
            return 0;
        }
        due.forEach(t -> pollOne(t.id()));
        return due.size();
    }

    /**
     * Interroge un transfert maintenant (utilisé aussi par l'action « synchroniser » du
     * back-office).
     */
    public void pollOne(long transferId) {
        TransferRow t = tx.execute(s -> transfers.findById(transferId).orElse(null));
        if (t == null || t.status() != TransferStatus.PROCESSING || t.providerReference() == null) {
            return;
        }
        Result<OrderStatus> result = gateway.status(t.providerReference());
        tx.executeWithoutResult(s -> applyStatus(transferId, result));
    }

    private void applyStatus(long transferId, Result<OrderStatus> result) {
        TransferRow t = transfers.lock(transferId).orElseThrow();
        if (t.status() != TransferStatus.PROCESSING) {
            return; // un autre travailleur ou le back-office l'a déjà réglé
        }
        if (!result.isOk()) {
            transfers.markPolled(t.id(), null);
            if (result.outcome() == Outcome.REJECTED && t.payoutHold() == null) {
                transfers.setHold(t.id(), "AMBIGUOUS_CREATE");
                transfers.appendHistory(
                        t.id(),
                        t.status(),
                        t.status(),
                        "Le fournisseur ne connaît pas la référence "
                                + t.providerReference()
                                + " ("
                                + result.code()
                                + ")",
                        "SYSTEM",
                        null,
                        null);
            }
            return;
        }
        OrderStatus status = result.value();
        transfers.markPolled(t.id(), status.rawStatus());
        switch (status.status()) {
            case PAID ->
                    lifecycle.move(
                            t,
                            TransferStatus.COMPLETED,
                            "Payé par le fournisseur",
                            "PROVIDER",
                            null,
                            "{\"providerStatus\":\"PAID\"}");
            case CANCEL -> failAndRefund(t, "Ordre annulé par le fournisseur");
            case LOCKED -> {
                if (!"PROVIDER_LOCKED".equals(t.payoutHold())) {
                    transfers.setHold(t.id(), "PROVIDER_LOCKED");
                    transfers.appendHistory(
                            t.id(),
                            t.status(),
                            t.status(),
                            "Ordre bloqué par le fournisseur (conformité) : aucun remboursement"
                                    + " automatique",
                            "PROVIDER",
                            null,
                            null);
                }
            }
            case WAITING_CONFIRMATION -> {
                if (t.payoutHold() == null
                        && t.providerConfirmedAt() == null
                        && !outbox.pending(
                                TransferService.OUTBOX_AGGREGATE, t.id(), EVENT_CONFIRM)) {
                    outbox.enqueue(
                            TransferService.OUTBOX_AGGREGATE,
                            t.id(),
                            EVENT_CONFIRM,
                            clock.instant()); // la confirmation s'était perdue
                }
            }
            default -> {
                if ("PROVIDER_LOCKED".equals(t.payoutHold())) {
                    transfers.clearHold(t.id()); // débloqué par le fournisseur
                }
            }
        }
    }

    // ------------------------------------------------------------------ utilitaires

    private static Duration backoff(int attempt) {
        long seconds = 30L * (1L << Math.min(Math.max(attempt - 1, 0), 12));
        Duration delay = Duration.ofSeconds(seconds);
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }
}
