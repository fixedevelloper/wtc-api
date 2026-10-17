package com.wtc.customer.application;

import com.wtc.customer.application.port.DestinationRepository;
import com.wtc.customer.application.port.PartyRepository;
import com.wtc.customer.application.port.PartyRepository.PartyRecord;
import com.wtc.customer.domain.Masking;
import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.security.SecretCipher;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expéditeurs et bénéficiaires d'un client. Tout est borné au client du jeton : une référence vers
 * la partie d'un autre client répond « introuvable », exactement comme une référence inexistante
 * (pas d'énumération).
 */
@Service
public class PartyService {

    private final PartyRepository parties;
    private final DestinationRepository destinations;
    private final CustomerSupport support;
    private final SecretCipher cipher;
    private final AuditTrail audit;
    private final Clock clock;

    PartyService(
            PartyRepository parties,
            DestinationRepository destinations,
            CustomerSupport support,
            SecretCipher cipher,
            AuditTrail audit,
            Clock clock) {
        this.parties = parties;
        this.destinations = destinations;
        this.support = support;
        this.cipher = cipher;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public Paged<PartyView> list(
            UUID customerId,
            String role,
            String search,
            int page,
            int size,
            String sortField,
            boolean ascending) {
        long id = support.load(customerId).id();
        Paged<PartyRecord> result =
                parties.listOfCustomer(id, role, search, page, size, sortField, ascending);
        return new Paged<>(
                result.content().stream().map(r -> view(r, false)).toList(), result.total());
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public PartyView get(UUID customerId, String role, UUID partyId) {
        return view(owned(customerId, role, partyId, false), false);
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public PartyView create(
            UUID customerId,
            UUID actorUserId,
            String role,
            PartyData data,
            String idDocumentNumber) {
        long owner = support.loadActive(customerId).id();
        validate(data);
        UUID publicId = UUID.randomUUID();
        parties.insert(
                publicId,
                owner,
                role,
                data,
                encrypt(publicId, idDocumentNumber),
                Masking.last4(idDocumentNumber));
        audit.recordAs(
                actorUserId,
                "PARTY_CREATED",
                "PARTY",
                publicId.toString(),
                null,
                "{\"role\":\"" + role + "\"}");
        return view(parties.find(publicId).orElseThrow(), false);
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public PartyView update(
            UUID customerId,
            UUID actorUserId,
            String role,
            UUID partyId,
            PartyData data,
            String idDocumentNumber) {
        PartyRecord party = owned(customerId, role, partyId, true);
        return replace(party, actorUserId, data, idDocumentNumber, "PARTY_UPDATED", false);
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public void archive(UUID customerId, UUID actorUserId, String role, UUID partyId) {
        PartyRecord party = owned(customerId, role, partyId, true);
        parties.archive(party.id(), clock.instant());
        audit.recordAs(actorUserId, "PARTY_ARCHIVED", "PARTY", partyId.toString(), null, null);
    }

    // ------------------------------------------------------------------ administration

    @PreAuthorize("hasAuthority('CUSTOMER_READ')")
    @Transactional(readOnly = true)
    public Paged<PartyView> adminList(
            String role,
            UUID customerId,
            String search,
            int page,
            int size,
            String sortField,
            boolean ascending) {
        Paged<PartyRecord> result =
                parties.adminList(role, customerId, search, page, size, sortField, ascending);
        return new Paged<>(
                result.content().stream().map(r -> view(r, true)).toList(), result.total());
    }

    @PreAuthorize("hasAuthority('CUSTOMER_READ')")
    @Transactional(readOnly = true)
    public PartyView adminGet(UUID partyId) {
        return view(found(partyId), true);
    }

    @PreAuthorize("hasAuthority('CUSTOMER_WRITE')")
    @Transactional
    public PartyView adminUpdate(
            UUID actorUserId, UUID partyId, PartyData data, String idDocumentNumber) {
        return replace(
                found(partyId),
                actorUserId,
                data,
                idDocumentNumber,
                "PARTY_UPDATED_BY_ADMIN",
                true);
    }

    // ------------------------------------------------------------------ interne

    private PartyView replace(
            PartyRecord party,
            UUID actorUserId,
            PartyData data,
            String idDocumentNumber,
            String action,
            boolean admin) {
        validate(data);
        boolean replaceNumber = idDocumentNumber != null && !idDocumentNumber.isBlank();
        // Numéro absent = on garde celui qui est enregistré (le client ne le revoit jamais, il ne
        // peut donc pas le renvoyer).
        parties.update(
                party.id(),
                data,
                replaceNumber,
                replaceNumber ? encrypt(party.publicId(), idDocumentNumber) : null,
                replaceNumber ? Masking.last4(idDocumentNumber) : null);
        audit.recordAs(actorUserId, action, "PARTY", party.publicId().toString(), null, null);
        return view(parties.find(party.publicId()).orElseThrow(), admin);
    }

    private void validate(PartyData d) {
        PartyRules.requireConsistent(
                d.kind(),
                d.firstName(),
                d.lastName(),
                d.businessName(),
                d.birthDate(),
                d.businessRegisteredOn(),
                LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        support.requireEnabledCountry(d.countryCode(), "countryCode");
    }

    /** Partie du client, du bon rôle, non archivée ; sinon « introuvable ». */
    PartyRecord owned(UUID customerId, String role, UUID partyId, boolean forWrite) {
        long ownerId = (forWrite ? support.loadActive(customerId) : support.load(customerId)).id();
        return parties.find(partyId)
                .filter(p -> p.customerId() == ownerId && p.role().equals(role))
                .orElseThrow(() -> new DomainException(ErrorCode.PARTY_NOT_FOUND, "Introuvable."));
    }

    /**
     * Contact du client avec son numéro de pièce en clair, pour le module transfert (appel
     * interne). Un contact d'un autre client, d'un autre rôle ou archivé répond « introuvable ».
     */
    @Transactional(readOnly = true)
    public TransferParty forTransfer(UUID customerId, String role, UUID partyId) {
        PartyRecord record = owned(customerId, role, partyId, false);
        String number =
                record.idNumberEncrypted() == null
                        ? null
                        : cipher.decrypt(record.idNumberEncrypted(), context(record.publicId()));
        return new TransferParty(
                record.id(), record.publicId(), record.role(), record.data(), number);
    }

    /** Identifiant interne du client (usage entre services du module). */
    long ownerId(UUID customerId) {
        return support.load(customerId).id();
    }

    /** Identifiant interne d'une partie existante (usage entre services du module). */
    long internalIdOf(UUID partyId) {
        return found(partyId).id();
    }

    private PartyRecord found(UUID partyId) {
        return parties.find(partyId)
                .orElseThrow(() -> new DomainException(ErrorCode.PARTY_NOT_FOUND, "Introuvable."));
    }

    private byte[] encrypt(UUID partyPublicId, String number) {
        return number == null || number.isBlank()
                ? null
                : cipher.encrypt(number.strip(), context(partyPublicId));
    }

    public static String context(UUID partyPublicId) {
        return "party-id-document:" + partyPublicId;
    }

    private PartyView view(PartyRecord r, boolean admin) {
        String masked =
                r.idNumberLast4() != null
                        ? "****" + r.idNumberLast4()
                        : (r.idNumberEncrypted() != null ? "****" : null);
        return new PartyView(
                r.publicId(),
                r.role(),
                r.data(),
                masked,
                r.createdAt(),
                admin ? r.customerPublicId() : null,
                admin ? r.customerName() : null);
    }
}
