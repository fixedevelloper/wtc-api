package com.wtc.customer.application;

import com.wtc.customer.application.port.DestinationRepository;
import com.wtc.customer.application.port.DestinationRepository.DestinationRecord;
import com.wtc.customer.application.port.PartyRepository.PartyRecord;
import com.wtc.customer.domain.Masking;
import com.wtc.reference.application.BankView;
import com.wtc.reference.application.MobileOperatorView;
import com.wtc.reference.application.ReferenceService;
import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import com.wtc.shared.security.SecretCipher;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comptes bancaires et portefeuilles mobiles d'un bénéficiaire. Numéros chiffrés au repos, jamais
 * renvoyés en clair.
 */
@Service
public class DestinationService {

    private static final String BENEFICIARY = "BENEFICIARY";
    private static final Pattern BANK_ACCOUNT = Pattern.compile("^[A-Z0-9]{4,34}$");
    private static final Pattern MOBILE_NUMBER = Pattern.compile("^\\+?[0-9]{6,15}$");
    private static final Pattern SWIFT =
            Pattern.compile("^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$");
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern ROUTING = Pattern.compile("^[A-Z0-9]{4,20}$");

    private final DestinationRepository destinations;
    private final PartyService parties;
    private final ReferenceService references;
    private final SecretCipher cipher;
    private final AuditTrail audit;
    private final Clock clock;

    DestinationService(
            DestinationRepository destinations,
            PartyService parties,
            ReferenceService references,
            SecretCipher cipher,
            AuditTrail audit,
            Clock clock) {
        this.destinations = destinations;
        this.parties = parties;
        this.references = references;
        this.cipher = cipher;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public List<DestinationView> list(UUID customerId, UUID beneficiaryId) {
        PartyRecord party = parties.owned(customerId, BENEFICIARY, beneficiaryId, false);
        return destinations.list(party.id()).stream().map(this::view).toList();
    }

    @PreAuthorize("hasAuthority('CUSTOMER_READ')")
    @Transactional(readOnly = true)
    public List<DestinationView> adminList(UUID partyId) {
        PartyView party = parties.adminGet(partyId); // vérifie l'existence
        return destinations.list(internalId(party.id())).stream().map(this::view).toList();
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public DestinationView create(
            UUID customerId, UUID actorUserId, UUID beneficiaryId, DestinationData data) {
        PartyRecord party = parties.owned(customerId, BENEFICIARY, beneficiaryId, true);
        Normalized n = validate(data);
        UUID publicId = UUID.randomUUID();
        boolean first = destinations.list(party.id()).isEmpty();
        boolean makeDefault = first || data.isDefault();
        if (makeDefault) {
            destinations.clearDefault(party.id());
        }
        destinations.insert(record(0, publicId, party.id(), data, n, makeDefault));
        audit.recordAs(
                actorUserId,
                "DESTINATION_CREATED",
                "DESTINATION",
                publicId.toString(),
                null,
                "{\"kind\":\"" + data.kind() + "\"}");
        return view(destinations.find(party.id(), publicId).orElseThrow());
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public DestinationView update(
            UUID customerId,
            UUID actorUserId,
            UUID beneficiaryId,
            UUID destinationId,
            DestinationData data) {
        PartyRecord party = parties.owned(customerId, BENEFICIARY, beneficiaryId, true);
        DestinationRecord existing = find(party.id(), destinationId);
        Normalized n = validate(data);
        if (data.isDefault()) {
            destinations.clearDefault(party.id());
        }
        destinations.update(
                record(existing.id(), existing.publicId(), party.id(), data, n, data.isDefault()));
        destinations.promoteMostRecent(party.id());
        audit.recordAs(
                actorUserId,
                "DESTINATION_UPDATED",
                "DESTINATION",
                destinationId.toString(),
                null,
                null);
        return view(destinations.find(party.id(), destinationId).orElseThrow());
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public void archive(UUID customerId, UUID actorUserId, UUID beneficiaryId, UUID destinationId) {
        PartyRecord party = parties.owned(customerId, BENEFICIARY, beneficiaryId, true);
        DestinationRecord existing = find(party.id(), destinationId);
        destinations.archive(existing.id(), clock.instant());
        destinations.promoteMostRecent(party.id());
        audit.recordAs(
                actorUserId,
                "DESTINATION_ARCHIVED",
                "DESTINATION",
                destinationId.toString(),
                null,
                null);
    }

    /**
     * Destination appartenant à ce client (appel interne, sans contrôle de rôle : le module
     * transfert l'utilise pour les devis et la création de transferts). Une destination d'un autre
     * client répond « introuvable ».
     */
    @Transactional(readOnly = true)
    public DestinationView resolve(UUID customerId, UUID destinationId) {
        long owner = parties.ownerId(customerId);
        return destinations
                .findOfCustomer(owner, destinationId)
                .map(this::view)
                .orElseThrow(
                        () -> new DomainException(ErrorCode.DESTINATION_NOT_FOUND, "Introuvable."));
    }

    /**
     * Destination avec son numéro en clair, pour l'envoi (appel interne, même contrôle
     * d'appartenance que {@link #resolve}).
     */
    @Transactional(readOnly = true)
    public TransferDestination forTransfer(UUID customerId, UUID destinationId) {
        long owner = parties.ownerId(customerId);
        DestinationRecord r =
                destinations
                        .findOfCustomer(owner, destinationId)
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.DESTINATION_NOT_FOUND, "Introuvable."));
        return new TransferDestination(
                r.id(),
                r.publicId(),
                r.partyId(),
                r.kind(),
                r.countryCode(),
                r.bankId(),
                r.mobileOperatorId(),
                r.accountHolderName(),
                cipher.decrypt(r.accountNumberEncrypted(), context(r.publicId())),
                r.accountNumberMasked(),
                r.swiftCode(),
                r.ifscCode(),
                r.routingNumber());
    }

    // ------------------------------------------------------------------ interne

    private record Normalized(String accountNumber, String swift, String ifsc, String routing) {}

    private DestinationRecord find(long partyId, UUID destinationId) {
        return destinations
                .find(partyId, destinationId)
                .orElseThrow(
                        () -> new DomainException(ErrorCode.DESTINATION_NOT_FOUND, "Introuvable."));
    }

    private DestinationRecord record(
            long id,
            UUID publicId,
            long partyId,
            DestinationData d,
            Normalized n,
            boolean isDefault) {
        return new DestinationRecord(
                id,
                publicId,
                partyId,
                d.kind(),
                d.countryCode(),
                "BANK_ACCOUNT".equals(d.kind()) ? d.bankId() : null,
                "MOBILE_WALLET".equals(d.kind()) ? d.mobileOperatorId() : null,
                d.accountHolderName(),
                cipher.encrypt(n.accountNumber(), context(publicId)),
                Masking.mask(n.accountNumber()),
                n.swift(),
                n.ifsc(),
                n.routing(),
                isDefault);
    }

    private Normalized validate(DestinationData d) {
        if (!references.canReceive(d.countryCode())) {
            throw invalid(
                    "countryCode",
                    "UnsupportedCountry",
                    "Ce pays ne peut pas recevoir de paiements.");
        }
        String number =
                d.accountNumber() == null
                        ? ""
                        : d.accountNumber().replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if ("BANK_ACCOUNT".equals(d.kind())) {
            if (d.bankId() == null) {
                throw invalid("bankId", "Required", "La banque est obligatoire.");
            }
            if (d.mobileOperatorId() != null) {
                throw invalid(
                        "mobileOperatorId",
                        "NotAllowed",
                        "Un compte bancaire n'a pas d'opérateur mobile.");
            }
            BankView bank =
                    references
                            .findBank(d.bankId())
                            .orElseThrow(
                                    () ->
                                            invalid(
                                                    "bankId",
                                                    "Unknown",
                                                    "Banque inconnue ou désactivée."));
            if (!bank.countryCode().equals(d.countryCode())) {
                throw invalid(
                        "bankId",
                        "CountryMismatch",
                        "Cette banque n'est pas dans le pays indiqué.");
            }
            if (!BANK_ACCOUNT.matcher(number).matches()) {
                throw invalid(
                        "accountNumber",
                        "Format",
                        "Numéro de compte invalide (4 à 34 lettres ou chiffres).");
            }
        } else if ("MOBILE_WALLET".equals(d.kind())) {
            if (d.mobileOperatorId() == null) {
                throw invalid(
                        "mobileOperatorId", "Required", "L'opérateur mobile est obligatoire.");
            }
            if (d.bankId() != null) {
                throw invalid("bankId", "NotAllowed", "Un portefeuille mobile n'a pas de banque.");
            }
            MobileOperatorView operator =
                    references
                            .findMobileOperator(d.mobileOperatorId())
                            .orElseThrow(
                                    () ->
                                            invalid(
                                                    "mobileOperatorId",
                                                    "Unknown",
                                                    "Opérateur inconnu ou désactivé."));
            if (!operator.countryCode().equals(d.countryCode())) {
                throw invalid(
                        "mobileOperatorId",
                        "CountryMismatch",
                        "Cet opérateur n'est pas dans le pays indiqué.");
            }
            if (!operator.supportsPayout()) {
                throw invalid(
                        "mobileOperatorId",
                        "PayoutUnsupported",
                        "Cet opérateur ne reçoit pas de paiements.");
            }
            if (!MOBILE_NUMBER.matcher(number).matches()) {
                throw invalid(
                        "accountNumber", "Format", "Numéro de mobile invalide (6 à 15 chiffres).");
            }
        } else {
            throw invalid("kind", "Invalid", "BANK_ACCOUNT ou MOBILE_WALLET.");
        }
        String swift = upper(d.swiftCode());
        String ifsc = upper(d.ifscCode());
        String routing = upper(d.routingNumber());
        if (swift != null && !SWIFT.matcher(swift).matches()) {
            throw invalid("swiftCode", "Format", "Code SWIFT/BIC invalide.");
        }
        if (ifsc != null && !IFSC.matcher(ifsc).matches()) {
            throw invalid("ifscCode", "Format", "Code IFSC invalide.");
        }
        if (routing != null && !ROUTING.matcher(routing).matches()) {
            throw invalid("routingNumber", "Format", "Numéro d'acheminement invalide.");
        }
        return new Normalized(number, swift, ifsc, routing);
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    private static DomainException invalid(String field, String code, String message) {
        return new DomainException(
                ErrorCode.VALIDATION_FAILED,
                message,
                List.of(new FieldViolation(field, code, message)));
    }

    public static String context(UUID destinationPublicId) {
        return "destination-account:" + destinationPublicId;
    }

    private DestinationView view(DestinationRecord r) {
        BankView bank = r.bankId() == null ? null : references.findBank(r.bankId()).orElse(null);
        MobileOperatorView operator =
                r.mobileOperatorId() == null
                        ? null
                        : references.findMobileOperator(r.mobileOperatorId()).orElse(null);
        return new DestinationView(
                r.publicId(),
                r.kind(),
                r.countryCode(),
                bank,
                operator,
                r.accountHolderName(),
                r.accountNumberMasked(),
                r.swiftCode(),
                r.ifscCode(),
                r.routingNumber(),
                r.isDefault());
    }

    private long internalId(UUID partyPublicId) {
        return parties.internalIdOf(partyPublicId);
    }
}
