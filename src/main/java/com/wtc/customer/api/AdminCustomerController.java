package com.wtc.customer.api;

import com.wtc.customer.application.AdminCustomerService;
import com.wtc.customer.application.AdminCustomerSummary;
import com.wtc.customer.application.CustomerProfile;
import com.wtc.customer.application.DestinationService;
import com.wtc.customer.application.KycDocumentView;
import com.wtc.customer.application.KycOverview;
import com.wtc.customer.application.KycService;
import com.wtc.customer.application.PartyService;
import com.wtc.shared.web.PageParams;
import com.wtc.shared.web.PageResponse;
import com.wtc.shared.web.Paged;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Back-office : clients, KYC, expéditeurs et bénéficiaires. Les permissions sont vérifiées dans les
 * services.
 */
@RestController
@RequestMapping("/api/admin")
class AdminCustomerController {

    private static final Set<String> CUSTOMER_SORT =
            Set.of("createdAt", "displayName", "kycStatus", "status");
    private static final Set<String> PARTY_SORT =
            Set.of("createdAt", "firstName", "lastName", "businessName");

    private final AdminCustomerService customers;
    private final KycService kyc;
    private final PartyService parties;
    private final DestinationService destinations;

    AdminCustomerController(
            AdminCustomerService customers,
            KycService kyc,
            PartyService parties,
            DestinationService destinations) {
        this.customers = customers;
        this.kyc = kyc;
        this.parties = parties;
        this.destinations = destinations;
    }

    record UpdateStatus(
            @NotNull @Pattern(regexp = "ACTIVE|SUSPENDED|CLOSED") String status,
            @Size(max = 500) String reason) {}

    record Review(
            @NotNull @Pattern(regexp = "APPROVE|REJECT") String decision,
            @Size(max = 500) String reason) {}

    @GetMapping("/customers")
    PageResponse<AdminCustomerSummary> listCustomers(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) @Size(max = 100) String search,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|SUSPENDED|CLOSED")
                    String status,
            @RequestParam(required = false)
                    @Pattern(regexp = "NOT_SUBMITTED|PENDING|VERIFIED|REJECTED|EXPIRED")
                    String kycStatus) {
        PageParams order = PageParams.parse(sort, CUSTOMER_SORT, "createdAt", false);
        var result =
                customers.list(
                        CustomerMeController.blankToNull(search),
                        status,
                        kycStatus,
                        page,
                        size,
                        order.field(),
                        order.ascending());
        return Paged.of(result.content(), page, size, result.total());
    }

    @GetMapping("/customers/{customerId}")
    CustomerProfile getCustomer(@PathVariable UUID customerId) {
        return customers.get(customerId);
    }

    @PatchMapping("/customers/{customerId}")
    CustomerProfile updateCustomerStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID customerId,
            @Valid @RequestBody UpdateStatus body) {
        return customers.updateStatus(Claims.userId(jwt), customerId, body.status(), body.reason());
    }

    @GetMapping("/customers/{customerId}/kyc")
    KycOverview getCustomerKyc(@PathVariable UUID customerId) {
        return kyc.adminOverview(customerId);
    }

    @PostMapping("/customers/{customerId}/kyc/documents/{documentId}/review")
    KycDocumentView reviewKycDocument(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID customerId,
            @PathVariable UUID documentId,
            @Valid @RequestBody Review body) {
        return kyc.review(
                Claims.userId(jwt),
                customerId,
                documentId,
                "APPROVE".equals(body.decision()),
                body.reason());
    }

    @GetMapping("/parties")
    PageResponse<PartyResponses.Party> listParties(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) @Size(max = 100) String search,
            @RequestParam(required = false) @Pattern(regexp = "SENDER|BENEFICIARY") String role,
            @RequestParam(required = false) UUID customerId) {
        PageParams order = PageParams.parse(sort, PARTY_SORT, "createdAt", false);
        var result =
                parties.adminList(
                        role,
                        customerId,
                        CustomerMeController.blankToNull(search),
                        page,
                        size,
                        order.field(),
                        order.ascending());
        return Paged.of(
                result.content().stream()
                        .map(v -> PartyResponses.Party.from(v, destinations.adminList(v.id())))
                        .toList(),
                page,
                size,
                result.total());
    }

    @GetMapping("/parties/{partyId}")
    PartyResponses.Party getParty(@PathVariable UUID partyId) {
        return PartyResponses.Party.from(
                parties.adminGet(partyId), destinations.adminList(partyId));
    }

    @PutMapping("/parties/{partyId}")
    PartyResponses.Party updateParty(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID partyId,
            @Valid @RequestBody PartyRequests.PartyInput body) {
        var updated =
                parties.adminUpdate(
                        Claims.userId(jwt), partyId, body.toData(), body.idDocumentNumber());
        return PartyResponses.Party.from(updated, destinations.adminList(partyId));
    }
}
