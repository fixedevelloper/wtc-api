package com.wtc.transfer.application;

import com.wtc.customer.application.CustomerAccounts;
import com.wtc.customer.application.CustomerForTransfer;
import com.wtc.customer.application.DestinationService;
import com.wtc.customer.application.PartyService;
import com.wtc.customer.application.TransferDestination;
import com.wtc.customer.application.TransferParty;
import com.wtc.reference.application.BankRouting;
import com.wtc.reference.application.BankView;
import com.wtc.reference.application.MobileOperatorView;
import com.wtc.reference.application.PricingQuote;
import com.wtc.reference.application.PricingService;
import com.wtc.reference.application.ReferenceService;
import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import com.wtc.shared.outbox.OutboxRepository;
import com.wtc.shared.security.SecretCipher;
import com.wtc.shared.security.Tokens;
import com.wtc.transfer.application.TransferSnapshot.ProviderCodes;
import com.wtc.transfer.application.port.TransferRepository;
import com.wtc.transfer.domain.PartyTypePair;
import com.wtc.transfer.domain.TransferReference;
import com.wtc.transfer.domain.TransferStatus;
import com.wtc.wallet.application.AccountRef;
import com.wtc.wallet.application.LedgerKind;
import com.wtc.wallet.application.LedgerService;
import com.wtc.wallet.application.PostingLeg;
import com.wtc.wallet.application.PostingRequest;
import com.wtc.wallet.application.WalletService;
import com.wtc.wallet.application.port.WalletRepository.WalletRow;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Création d'un transfert. Tout ce qui touche à l'argent se passe dans <b>une seule transaction
 * SQL</b> : validation, recalcul du devis, débit du portefeuille, création du transfert et de
 * l'ordre d'envoi (boîte d'envoi). Si une étape échoue, rien n'est débité ; si le processus meurt
 * après le commit, l'ordre d'envoi existe et sera traité. Aucun appel réseau n'est fait ici.
 */
@Service
public class TransferService {

    public static final String OUTBOX_AGGREGATE = "TRANSFER";
    public static final String EVENT_CREATE_ORDER = "payout.create";

    private final TransferRepository transfers;
    private final OutboxRepository outbox;
    private final CustomerAccounts customers;
    private final PartyService parties;
    private final DestinationService destinations;
    private final ReferenceService references;
    private final PricingService pricing;
    private final WalletService wallets;
    private final LedgerService ledger;
    private final TransferLifecycle lifecycle;
    private final SecretCipher cipher;
    private final JsonMapper json;
    private final AuditTrail audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    TransferService(
            TransferRepository transfers,
            OutboxRepository outbox,
            CustomerAccounts customers,
            PartyService parties,
            DestinationService destinations,
            ReferenceService references,
            PricingService pricing,
            WalletService wallets,
            LedgerService ledger,
            TransferLifecycle lifecycle,
            SecretCipher cipher,
            JsonMapper json,
            AuditTrail audit,
            Clock clock,
            PlatformTransactionManager txManager) {
        this.transfers = transfers;
        this.outbox = outbox;
        this.customers = customers;
        this.parties = parties;
        this.destinations = destinations;
        this.references = references;
        this.pricing = pricing;
        this.wallets = wallets;
        this.ledger = ledger;
        this.lifecycle = lifecycle;
        this.cipher = cipher;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Un transfert du client et son historique d'états (le client ne voit jamais les tentatives
     * auprès du fournisseur).
     */
    public record MineDetail(TransferRow transfer, List<TransferRepository.HistoryEntry> history) {
        public MineDetail {
            history = List.copyOf(history);
        }
    }

    // ------------------------------------------------------------------ création

    @PreAuthorize("hasRole('CUSTOMER')")
    public MineDetail createForCustomer(
            UUID customerId, UUID actor, String idempotencyKey, CreateTransferCommand cmd) {
        return withHistory(
                create(
                        customerId,
                        actor,
                        "CUSTOMER",
                        idempotencyKey,
                        new CreateTransferCommand(
                                cmd.senderId(),
                                cmd.beneficiaryId(),
                                cmd.destinationId(),
                                cmd.sourceAmount(),
                                cmd.purposeCode(),
                                cmd.sourceOfFundsCode(),
                                cmd.relationshipCode(),
                                cmd.comment(),
                                cmd.expectedTotalDebit(),
                                false,
                                null)));
    }

    /**
     * Saisie par le back-office pour un client ; avec {@code offlineReceipt}, les fonds reçus hors
     * plateforme sont d'abord crédités.
     */
    @PreAuthorize("hasAuthority('TRANSFER_CREATE')")
    public TransferRow createForCustomerByStaff(
            UUID customerId, UUID actor, String idempotencyKey, CreateTransferCommand cmd) {
        return create(customerId, actor, "ADMIN", idempotencyKey, cmd);
    }

    private TransferRow create(
            UUID customerId, UUID actor, String actorType, String key, CreateTransferCommand cmd) {
        try {
            return tx.execute(status -> doCreate(customerId, actor, actorType, key, cmd));
        } catch (DuplicateKeyException race) {
            // Deux demandes simultanées avec la même clé : la perdante est annulée (débit compris),
            // on renvoie le résultat de la gagnante.
            return tx.execute(
                    status ->
                            replay(
                                            customers.forTransfer(customerId),
                                            key,
                                            fingerprint(customerId, key, cmd))
                                    .orElseThrow(() -> race));
        }
    }

    private TransferRow doCreate(
            UUID customerId, UUID actor, String actorType, String key, CreateTransferCommand cmd) {
        CustomerForTransfer customer = customers.forTransfer(customerId);
        if (!customer.isActive()) {
            throw new DomainException(
                    ErrorCode.CUSTOMER_NOT_ACTIVE, "Le compte du client n'est pas actif.");
        }
        if (!customer.isVerified()) {
            throw new DomainException(
                    ErrorCode.KYC_REQUIRED,
                    "La vérification d'identité du client doit être terminée avant d'envoyer de"
                            + " l'argent.");
        }
        String fingerprint = fingerprint(customerId, key, cmd);
        var existing = replay(customer, key, fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }

        TransferParty sender = parties.forTransfer(customerId, "SENDER", cmd.senderId());
        TransferParty beneficiary =
                parties.forTransfer(customerId, "BENEFICIARY", cmd.beneficiaryId());
        TransferDestination destination = destinations.forTransfer(customerId, cmd.destinationId());
        if (destination.partyInternalId() != beneficiary.internalId()) {
            throw invalid(
                    "destinationId",
                    "NotOfBeneficiary",
                    "Cette destination n'appartient pas à ce bénéficiaire.");
        }
        String pair = PartyTypePair.of(sender.data().kind(), beneficiary.data().kind());
        ProviderCodes codes = providerCodes(cmd, destination, pair);

        List<FieldViolation> incomplete =
                WaceRules.check(sender, beneficiary, LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        if (!incomplete.isEmpty()) {
            throw new DomainException(
                    ErrorCode.PARTY_INCOMPLETE,
                    "Des informations obligatoires pour l'envoi sont manquantes ou invalides.",
                    incomplete);
        }

        String currency = wallets.primaryCurrency(customerId);
        PricingQuote quote = pricing.quote(currency, destination.countryCode(), cmd.sourceAmount());
        if (cmd.expectedTotalDebit() != null
                && cmd.expectedTotalDebit().compareTo(quote.totalDebit()) != 0) {
            throw new DomainException(
                    ErrorCode.QUOTE_CHANGED,
                    "Le total a changé depuis votre devis ("
                            + quote.totalDebit().toPlainString()
                            + " "
                            + currency
                            + "). Vérifiez et confirmez à nouveau.");
        }
        enforceLimit(currency, quote);

        WalletRow wallet = wallets.ensureWallet(customerId, currency);
        UUID publicId = UUID.randomUUID();
        if (cmd.offlineReceipt()) {
            ledger.post(
                    new PostingRequest(
                            "transfer:" + publicId + ":offline-receipt",
                            LedgerKind.TOP_UP,
                            "Fonds reçus hors plateforme : " + nz(cmd.offlineReceiptReference()),
                            List.of(
                                    PostingLeg.debit(
                                            AccountRef.platform("PLATFORM_OFFLINE_RECEIPTS"),
                                            quote.totalDebit(),
                                            currency),
                                    PostingLeg.credit(
                                            AccountRef.wallet(wallet.id()),
                                            quote.totalDebit(),
                                            currency)),
                            actor,
                            "TRANSFER",
                            null));
        }

        BankView bank =
                destination.bankId() == null
                        ? null
                        : references.findBank(destination.bankId()).orElse(null);
        MobileOperatorView operator =
                destination.mobileOperatorId() == null
                        ? null
                        : references
                                .findMobileOperator(destination.mobileOperatorId())
                                .orElse(null);
        String snapshot =
                TransferSnapshot.build(
                        json,
                        cipher,
                        publicId,
                        sender,
                        beneficiary,
                        destination,
                        bank,
                        operator,
                        quote,
                        codes);
        String reference =
                TransferReference.format(
                        Year.now(clock.withZone(ZoneOffset.UTC)), transfers.nextReferenceNumber());
        String kind = "BANK_ACCOUNT".equals(destination.kind()) ? "BANK_TRANSFER" : "MOBILE_MONEY";

        // Le débit est posé juste avant l'insertion : la ligne du transfert référence l'écriture,
        // la contrainte de base l'impose.
        var provisional =
                new TransferRow(
                        0,
                        publicId,
                        reference,
                        customer.internalId(),
                        customerId,
                        customer.displayName(),
                        wallet.id(),
                        sender.internalId(),
                        beneficiary.internalId(),
                        destination.internalId(),
                        kind,
                        cmd.offlineReceipt() ? "OFFLINE_RECEIPT" : "WALLET",
                        pair,
                        quote.sourceAmount(),
                        quote.fee(),
                        quote.totalDebit(),
                        currency,
                        quote.destinationAmount(),
                        quote.destinationCurrency(),
                        quote.fxRate(),
                        quote.feeScheduleId(),
                        cmd.purposeCode(),
                        cmd.sourceOfFundsCode(),
                        cmd.relationshipCode(),
                        cmd.comment(),
                        TransferStatus.CREATED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        snapshot,
                        null,
                        null,
                        fingerprint,
                        clock.instant(),
                        null);
        var debit = lifecycle.debit(provisional, actor);

        long id =
                transfers.insert(
                        new NewTransfer(
                                publicId,
                                reference,
                                customer.internalId(),
                                wallet.id(),
                                sender.internalId(),
                                beneficiary.internalId(),
                                destination.internalId(),
                                kind,
                                provisional.fundingSource(),
                                pair,
                                quote.sourceAmount(),
                                quote.fee(),
                                currency,
                                quote.destinationAmount(),
                                quote.destinationCurrency(),
                                quote.fxRate(),
                                quote.feeScheduleId(),
                                cmd.purposeCode(),
                                cmd.sourceOfFundsCode(),
                                cmd.relationshipCode(),
                                cmd.comment(),
                                key,
                                fingerprint,
                                snapshot,
                                debit.ledgerTransactionId(),
                                actor));
        transfers.appendHistory(
                id,
                null,
                TransferStatus.CREATED,
                "Transfert créé, portefeuille débité",
                actorType,
                actor,
                null);
        TransferRow created = transfers.findById(id).orElseThrow();
        lifecycle.move(
                created,
                TransferStatus.PAYOUT_PENDING,
                "Ordre d'envoi mis en file",
                "SYSTEM",
                null,
                null);
        outbox.enqueue(OUTBOX_AGGREGATE, id, EVENT_CREATE_ORDER, clock.instant());
        lifecycle.announce(transfers.findById(id).orElseThrow(), "transfer.created");
        audit.recordAs(
                actor,
                "TRANSFER_CREATED",
                "TRANSFER",
                publicId.toString(),
                null,
                "{\"reference\":\""
                        + reference
                        + "\",\"amount\":"
                        + quote.sourceAmount().toPlainString()
                        + ",\"currency\":\""
                        + currency
                        + "\"}");
        return transfers.findById(id).orElseThrow();
    }

    // ------------------------------------------------------------------ lecture

    @PreAuthorize("hasRole('CUSTOMER')")
    public TransferRepository.Page listMine(
            UUID customerId, int page, int size, boolean newestFirst) {
        return transfers.listOfCustomer(
                customers.forTransfer(customerId).internalId(), page, size, newestFirst);
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    public MineDetail getMine(UUID customerId, UUID transferId) {
        return withHistory(
                transfers
                        .findByPublicId(transferId)
                        .filter(t -> t.customerPublicId().equals(customerId))
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.TRANSFER_NOT_FOUND,
                                                "Transfert introuvable.")));
    }

    /** Reçu PDF d'un transfert du client (les numéros y sont masqués). */
    @PreAuthorize("hasRole('CUSTOMER')")
    public byte[] receiptOf(UUID customerId, UUID transferId) {
        return ReceiptPdf.render(json, getMine(customerId, transferId).transfer());
    }

    private MineDetail withHistory(TransferRow t) {
        return new MineDetail(t, transfers.history(t.id()));
    }

    @PreAuthorize("hasAuthority('TRANSFER_READ')")
    public TransferRepository.Page search(
            TransferRepository.Filter filter, int page, int size, boolean newestFirst) {
        return transfers.search(filter, page, size, newestFirst);
    }

    @PreAuthorize("hasAuthority('TRANSFER_READ')")
    public TransferRow get(UUID transferId) {
        return transfers
                .findByPublicId(transferId)
                .orElseThrow(
                        () ->
                                new DomainException(
                                        ErrorCode.TRANSFER_NOT_FOUND, "Transfert introuvable."));
    }

    // ------------------------------------------------------------------ interne

    private java.util.Optional<TransferRow> replay(
            CustomerForTransfer customer, String key, String fingerprint) {
        return transfers
                .findByIdempotency(customer.internalId(), key)
                .map(
                        found -> {
                            if (!fingerprint.equals(found.requestFingerprint())) {
                                throw new DomainException(
                                        ErrorCode.IDEMPOTENCY_KEY_REUSED,
                                        "Cette clé d'idempotence a déjà servi pour une autre"
                                                + " demande.");
                            }
                            return found;
                        });
    }

    private ProviderCodes providerCodes(
            CreateTransferCommand cmd, TransferDestination destination, String pair) {
        String purpose = waceValue("TRANSFER_PURPOSE", pair, cmd.purposeCode(), "purposeCode");
        String origin =
                waceValue("SOURCE_OF_FUNDS", pair, cmd.sourceOfFundsCode(), "sourceOfFundsCode");
        String relation =
                waceValue("RELATIONSHIP", pair, cmd.relationshipCode(), "relationshipCode");
        if ("BANK_ACCOUNT".equals(destination.kind())) {
            BankRouting routing =
                    references
                            .bankRouting(destination.bankId(), destination.countryCode())
                            .orElseThrow(
                                    () ->
                                            new DomainException(
                                                    ErrorCode.DESTINATION_NOT_SUPPORTED,
                                                    "Cette banque n'est pas encore configurée pour"
                                                            + " l'envoi."));
            return new ProviderCodes(
                    routing.payerCode(),
                    routing.waceBankId(),
                    routing.bankCode(),
                    routing.bankName(),
                    origin,
                    purpose,
                    relation);
        }
        String payer =
                references
                        .operatorPayerCode(destination.mobileOperatorId())
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.DESTINATION_NOT_SUPPORTED,
                                                "Cet opérateur n'est pas encore configuré pour"
                                                        + " l'envoi."));
        return new ProviderCodes(payer, null, null, null, origin, purpose, relation);
    }

    private String waceValue(String category, String pair, String code, String field) {
        return references
                .waceReferenceCode(category, pair, code)
                .orElseThrow(
                        () ->
                                invalid(
                                        field,
                                        "Unknown",
                                        "Valeur inconnue pour ce type de transfert ("
                                                + pair
                                                + ")."));
    }

    private void enforceLimit(String currency, PricingQuote quote) {
        references
                .maxAmountPerTransfer()
                .ifPresent(
                        max -> {
                            if (currency.equals(references.defaultCurrency())
                                    && quote.sourceAmount().compareTo(max) > 0) {
                                throw new DomainException(
                                        ErrorCode.AMOUNT_LIMIT_EXCEEDED,
                                        "Le montant dépasse le plafond par transfert ("
                                                + max.toPlainString()
                                                + " "
                                                + currency
                                                + ").");
                            }
                        });
    }

    private static String fingerprint(UUID customerId, String key, CreateTransferCommand c) {
        return Tokens.sha256(
                String.join(
                        "|",
                        customerId.toString(),
                        String.valueOf(c.senderId()),
                        String.valueOf(c.beneficiaryId()),
                        String.valueOf(c.destinationId()),
                        c.sourceAmount() == null
                                ? ""
                                : c.sourceAmount().stripTrailingZeros().toPlainString(),
                        nz(c.purposeCode()),
                        nz(c.sourceOfFundsCode()),
                        nz(c.relationshipCode()),
                        nz(c.comment()),
                        c.expectedTotalDebit() == null
                                ? ""
                                : c.expectedTotalDebit().stripTrailingZeros().toPlainString(),
                        String.valueOf(c.offlineReceipt()),
                        nz(c.offlineReceiptReference())));
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static DomainException invalid(String field, String code, String message) {
        return new DomainException(
                ErrorCode.VALIDATION_FAILED,
                message,
                List.of(new FieldViolation(field, code, message)));
    }
}
