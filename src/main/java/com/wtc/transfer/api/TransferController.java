package com.wtc.transfer.api;

import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.security.JwtSubject;
import com.wtc.shared.web.PageParams;
import com.wtc.shared.web.PageResponse;
import com.wtc.shared.web.Paged;
import com.wtc.transfer.application.CreateTransferCommand;
import com.wtc.transfer.application.TransferAdminService;
import com.wtc.transfer.application.TransferRow;
import com.wtc.transfer.application.TransferService;
import com.wtc.transfer.application.port.TransferRepository;
import com.wtc.transfer.domain.TransferStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transferts : le client connecté et le back-office. Les permissions sont vérifiées dans les
 * services.
 */
@RestController
@RequestMapping("/api")
class TransferController {

    private static final String DECIMAL = "^\\d{1,15}(\\.\\d{1,4})?$";
    private static final Set<String> SORT = Set.of("createdAt");

    private final TransferService transfers;
    private final TransferAdminService admin;
    private final JsonMapper json;

    TransferController(TransferService transfers, TransferAdminService admin, JsonMapper json) {
        this.transfers = transfers;
        this.admin = admin;
        this.json = json;
    }

    record CreateRequest(
            @NotNull UUID senderId,
            @NotNull UUID beneficiaryId,
            @NotNull UUID destinationId,
            @NotNull @Pattern(regexp = DECIMAL) String sourceAmount,
            @NotBlank @Size(max = 60) String purposeCode,
            @NotBlank @Size(max = 60) String sourceOfFundsCode,
            @NotBlank @Size(max = 60) String relationshipCode,
            @Size(max = 255) String comment,
            @Pattern(regexp = DECIMAL) String expectedTotalDebit) {

        CreateTransferCommand toCommand(boolean offline, String receipt) {
            return new CreateTransferCommand(
                    senderId,
                    beneficiaryId,
                    destinationId,
                    new BigDecimal(sourceAmount),
                    purposeCode,
                    sourceOfFundsCode,
                    relationshipCode,
                    comment,
                    expectedTotalDebit == null ? null : new BigDecimal(expectedTotalDebit),
                    offline,
                    receipt);
        }
    }

    record AdminCreateRequest(
            @NotNull UUID customerId,
            @NotNull UUID senderId,
            @NotNull UUID beneficiaryId,
            @NotNull UUID destinationId,
            @NotNull @Pattern(regexp = DECIMAL) String sourceAmount,
            @NotBlank @Size(max = 60) String purposeCode,
            @NotBlank @Size(max = 60) String sourceOfFundsCode,
            @NotBlank @Size(max = 60) String relationshipCode,
            @Size(max = 255) String comment,
            @Pattern(regexp = DECIMAL) String expectedTotalDebit,
            @NotNull @Pattern(regexp = "WALLET|OFFLINE_RECEIPT") String fundingSource,
            @Size(max = 200) String receiptReference) {}

    record RefundRequest(@NotBlank @Size(min = 5, max = 500) String reason) {}

    record SyncRequest(@Size(max = 100) String providerReference) {}

    // ------------------------------------------------------------------ client

    @PostMapping("/me/transfers")
    ResponseEntity<TransferResponses.Detail> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 100) String key,
            @Valid @RequestBody CreateRequest body) {
        var created =
                transfers.createForCustomer(
                        customerId(jwt), JwtSubject.userId(jwt), key, body.toCommand(false, null));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TransferResponses.detail(json, created.transfer(), created.history()));
    }

    @GetMapping("/me/transfers")
    PageResponse<TransferResponses.Summary> listMine(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        PageParams order = PageParams.parse(sort, SORT, "createdAt", false);
        var result = transfers.listMine(customerId(jwt), page, size, !order.ascending());
        return Paged.of(
                result.content().stream().map(t -> TransferResponses.summary(json, t)).toList(),
                page,
                size,
                result.total());
    }

    @GetMapping("/me/transfers/{transferId}")
    TransferResponses.Detail getMine(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID transferId) {
        var mine = transfers.getMine(customerId(jwt), transferId);
        return TransferResponses.detail(json, mine.transfer(), mine.history());
    }

    @GetMapping(value = "/me/transfers/{transferId}/receipt", produces = "application/pdf")
    ResponseEntity<byte[]> receipt(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID transferId) {
        byte[] pdf = transfers.receiptOf(customerId(jwt), transferId);
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=\"recu-transfert.pdf\"")
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    // ------------------------------------------------------------------ back-office

    @GetMapping("/admin/transfers")
    PageResponse<TransferResponses.AdminSummary> search(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) @Size(max = 100) String search,
            @RequestParam(required = false) TransferStatus status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to) {
        PageParams order = PageParams.parse(sort, SORT, "createdAt", false);
        var filter =
                new TransferRepository.Filter(
                        status,
                        customerId,
                        from == null ? null : from.atStartOfDay().toInstant(ZoneOffset.UTC),
                        to == null ? null : to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC),
                        search == null || search.isBlank() ? null : search.strip());
        var result = transfers.search(filter, page, size, !order.ascending());
        return Paged.of(
                result.content().stream()
                        .map(t -> TransferResponses.adminSummary(json, t))
                        .toList(),
                page,
                size,
                result.total());
    }

    @GetMapping("/admin/transfers/{transferId}")
    TransferResponses.AdminDetail adminGet(@PathVariable UUID transferId) {
        return TransferResponses.adminDetail(json, admin.detail(transferId));
    }

    @PostMapping("/admin/transfers")
    ResponseEntity<TransferResponses.AdminDetail> adminCreate(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 100) String key,
            @Valid @RequestBody AdminCreateRequest body) {
        boolean offline = "OFFLINE_RECEIPT".equals(body.fundingSource());
        if (offline && (body.receiptReference() == null || body.receiptReference().isBlank())) {
            throw new DomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Le justificatif du paiement reçu hors plateforme est obligatoire.");
        }
        var cmd =
                new CreateTransferCommand(
                        body.senderId(),
                        body.beneficiaryId(),
                        body.destinationId(),
                        new BigDecimal(body.sourceAmount()),
                        body.purposeCode(),
                        body.sourceOfFundsCode(),
                        body.relationshipCode(),
                        body.comment(),
                        body.expectedTotalDebit() == null
                                ? null
                                : new BigDecimal(body.expectedTotalDebit()),
                        offline,
                        body.receiptReference());
        TransferRow created =
                transfers.createForCustomerByStaff(
                        body.customerId(), JwtSubject.userId(jwt), key, cmd);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TransferResponses.adminDetail(json, admin.detail(created.publicId())));
    }

    @PostMapping("/admin/transfers/{transferId}/refund")
    TransferResponses.AdminDetail refund(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID transferId,
            @Valid @RequestBody RefundRequest body) {
        return TransferResponses.adminDetail(
                json, admin.refund(JwtSubject.userId(jwt), transferId, body.reason()));
    }

    @PostMapping("/admin/transfers/{transferId}/sync")
    TransferResponses.AdminDetail sync(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID transferId,
            @Valid @RequestBody(required = false) SyncRequest body) {
        return TransferResponses.adminDetail(
                json,
                admin.sync(
                        JwtSubject.userId(jwt),
                        transferId,
                        body == null ? null : body.providerReference()));
    }

    private static UUID customerId(Jwt jwt) {
        String id = jwt.getClaimAsString("customer_id");
        if (id == null) {
            throw new DomainException(
                    ErrorCode.ACCESS_DENIED, "Ce compte n'a pas de fiche client.");
        }
        return UUID.fromString(id);
    }
}
