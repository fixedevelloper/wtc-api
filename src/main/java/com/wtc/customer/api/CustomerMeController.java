package com.wtc.customer.api;

import com.wtc.customer.application.CustomerProfile;
import com.wtc.customer.application.CustomerProfileService;
import com.wtc.customer.application.DestinationService;
import com.wtc.customer.application.KycDocumentView;
import com.wtc.customer.application.KycOverview;
import com.wtc.customer.application.KycService;
import com.wtc.customer.application.PartyService;
import com.wtc.customer.application.ProfileChanges;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.web.PageParams;
import com.wtc.shared.web.PageResponse;
import com.wtc.shared.web.Paged;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Espace du client connecté : profil, KYC, expéditeurs, bénéficiaires et destinations. Le client
 * est toujours celui du jeton ({@code customer_id}) ; aucun identifiant de client n'est accepté
 * dans l'URL.
 */
@RestController
@RequestMapping("/api/me")
class CustomerMeController {

    private static final Set<String> PARTY_SORT =
            Set.of("createdAt", "firstName", "lastName", "businessName");

    private final CustomerProfileService profiles;
    private final KycService kyc;
    private final PartyService parties;
    private final DestinationService destinations;

    CustomerMeController(
            CustomerProfileService profiles,
            KycService kyc,
            PartyService parties,
            DestinationService destinations) {
        this.profiles = profiles;
        this.kyc = kyc;
        this.parties = parties;
        this.destinations = destinations;
    }

    // ------------------------------------------------------------------ profil

    record UpdateProfile(
            @Pattern(regexp = "INDIVIDUAL|BUSINESS") String kind,
            @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @Size(max = 200) String businessName,
            @Size(max = 100) String businessType,
            LocalDate businessRegisteredOn,
            LocalDate birthDate,
            @Size(max = 100) String nationality,
            @Size(max = 100) String occupation,
            @Pattern(regexp = "M|F|OTHER") String gender,
            @Size(max = 20) String civility,
            @Size(max = 300) String addressLine,
            @Size(max = 100) String city,
            @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
            @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$") String phone,
            @Email @Size(max = 254) String email) {

        boolean isEmpty() {
            return kind == null
                    && firstName == null
                    && lastName == null
                    && businessName == null
                    && businessType == null
                    && businessRegisteredOn == null
                    && birthDate == null
                    && nationality == null
                    && occupation == null
                    && gender == null
                    && civility == null
                    && addressLine == null
                    && city == null
                    && countryCode == null
                    && phone == null
                    && email == null;
        }

        ProfileChanges toChanges() {
            return new ProfileChanges(
                    kind,
                    firstName,
                    lastName,
                    businessName,
                    businessType,
                    businessRegisteredOn,
                    birthDate,
                    nationality,
                    occupation,
                    gender,
                    civility,
                    addressLine,
                    city,
                    countryCode,
                    phone,
                    email);
        }
    }

    @PatchMapping
    CustomerProfile updateMe(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfile body) {
        if (body.isEmpty()) {
            throw new DomainException(
                    ErrorCode.MALFORMED_REQUEST, "Au moins un champ à modifier est requis.");
        }
        return profiles.update(Claims.customerId(jwt), Claims.userId(jwt), body.toChanges());
    }

    // ------------------------------------------------------------------ KYC

    @GetMapping("/kyc")
    KycOverview getKyc(@AuthenticationPrincipal Jwt jwt) {
        return kyc.overview(Claims.customerId(jwt));
    }

    @PostMapping(value = "/kyc/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<KycDocumentView> uploadKycDocument(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("documentType") String documentType,
            @RequestParam(value = "side", required = false) String side,
            @RequestParam(value = "expiresOn", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate expiresOn,
            @RequestParam("file") MultipartFile file)
            throws IOException {
        KycDocumentView created =
                kyc.upload(
                        Claims.customerId(jwt),
                        Claims.userId(jwt),
                        documentType,
                        side,
                        expiresOn,
                        file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/kyc/documents/{documentId}")
    KycDocumentView getKycDocument(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID documentId) {
        return kyc.document(Claims.customerId(jwt), documentId);
    }

    // ------------------------------------------------------------------ expéditeurs

    @GetMapping("/senders")
    PageResponse<PartyResponses.Party> listSenders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) @Size(max = 100) String search) {
        return list(jwt, "SENDER", page, size, sort, search);
    }

    @PostMapping("/senders")
    ResponseEntity<PartyResponses.Party> createSender(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PartyRequests.PartyInput body) {
        return created(jwt, "SENDER", body);
    }

    @GetMapping("/senders/{senderId}")
    PartyResponses.Party getSender(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID senderId) {
        return PartyResponses.Party.from(parties.get(Claims.customerId(jwt), "SENDER", senderId));
    }

    @PutMapping("/senders/{senderId}")
    PartyResponses.Party updateSender(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID senderId,
            @Valid @RequestBody PartyRequests.PartyInput body) {
        return PartyResponses.Party.from(
                parties.update(
                        Claims.customerId(jwt),
                        Claims.userId(jwt),
                        "SENDER",
                        senderId,
                        body.toData(),
                        body.idDocumentNumber()));
    }

    @DeleteMapping("/senders/{senderId}")
    ResponseEntity<Void> archiveSender(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID senderId) {
        parties.archive(Claims.customerId(jwt), Claims.userId(jwt), "SENDER", senderId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ bénéficiaires

    @GetMapping("/beneficiaries")
    PageResponse<PartyResponses.Party> listBeneficiaries(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) @Size(max = 100) String search) {
        return list(jwt, "BENEFICIARY", page, size, sort, search);
    }

    @PostMapping("/beneficiaries")
    ResponseEntity<PartyResponses.Party> createBeneficiary(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PartyRequests.PartyInput body) {
        return created(jwt, "BENEFICIARY", body);
    }

    @GetMapping("/beneficiaries/{beneficiaryId}")
    PartyResponses.Party getBeneficiary(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID beneficiaryId) {
        return PartyResponses.Party.from(
                parties.get(Claims.customerId(jwt), "BENEFICIARY", beneficiaryId));
    }

    @PutMapping("/beneficiaries/{beneficiaryId}")
    PartyResponses.Party updateBeneficiary(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID beneficiaryId,
            @Valid @RequestBody PartyRequests.PartyInput body) {
        return PartyResponses.Party.from(
                parties.update(
                        Claims.customerId(jwt),
                        Claims.userId(jwt),
                        "BENEFICIARY",
                        beneficiaryId,
                        body.toData(),
                        body.idDocumentNumber()));
    }

    @DeleteMapping("/beneficiaries/{beneficiaryId}")
    ResponseEntity<Void> archiveBeneficiary(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID beneficiaryId) {
        parties.archive(Claims.customerId(jwt), Claims.userId(jwt), "BENEFICIARY", beneficiaryId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ destinations

    @GetMapping("/beneficiaries/{beneficiaryId}/destinations")
    List<PartyResponses.Destination> listDestinations(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID beneficiaryId) {
        return destinations.list(Claims.customerId(jwt), beneficiaryId).stream()
                .map(PartyResponses.Destination::from)
                .toList();
    }

    @PostMapping("/beneficiaries/{beneficiaryId}/destinations")
    ResponseEntity<PartyResponses.Destination> createDestination(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID beneficiaryId,
            @Valid @RequestBody DestinationRequests.DestinationInput body) {
        var created =
                destinations.create(
                        Claims.customerId(jwt), Claims.userId(jwt), beneficiaryId, body.toData());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PartyResponses.Destination.from(created));
    }

    @PutMapping("/beneficiaries/{beneficiaryId}/destinations/{destinationId}")
    PartyResponses.Destination updateDestination(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID beneficiaryId,
            @PathVariable UUID destinationId,
            @Valid @RequestBody DestinationRequests.DestinationInput body) {
        return PartyResponses.Destination.from(
                destinations.update(
                        Claims.customerId(jwt),
                        Claims.userId(jwt),
                        beneficiaryId,
                        destinationId,
                        body.toData()));
    }

    @DeleteMapping("/beneficiaries/{beneficiaryId}/destinations/{destinationId}")
    ResponseEntity<Void> archiveDestination(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID beneficiaryId,
            @PathVariable UUID destinationId) {
        destinations.archive(
                Claims.customerId(jwt), Claims.userId(jwt), beneficiaryId, destinationId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ interne

    private PageResponse<PartyResponses.Party> list(
            Jwt jwt, String role, int page, int size, String sort, String search) {
        PageParams order = PageParams.parse(sort, PARTY_SORT, "createdAt", false);
        var result =
                parties.list(
                        Claims.customerId(jwt),
                        role,
                        blankToNull(search),
                        page,
                        size,
                        order.field(),
                        order.ascending());
        return Paged.of(
                result.content().stream().map(PartyResponses.Party::from).toList(),
                page,
                size,
                result.total());
    }

    private ResponseEntity<PartyResponses.Party> created(
            Jwt jwt, String role, PartyRequests.PartyInput body) {
        var created =
                parties.create(
                        Claims.customerId(jwt),
                        Claims.userId(jwt),
                        role,
                        body.toData(),
                        body.idDocumentNumber());
        return ResponseEntity.status(HttpStatus.CREATED).body(PartyResponses.Party.from(created));
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
