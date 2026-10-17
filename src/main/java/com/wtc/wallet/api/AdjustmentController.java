package com.wtc.wallet.api;

import com.wtc.shared.security.JwtSubject;
import com.wtc.shared.web.PageParams;
import com.wtc.shared.web.PageResponse;
import com.wtc.shared.web.Paged;
import com.wtc.wallet.application.AdjustmentView;
import com.wtc.wallet.application.BalanceAdjustmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
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

/** Ajustements manuels de solde à double validation. Permissions vérifiées dans le service. */
@RestController
@RequestMapping("/api/admin/balance-adjustments")
class AdjustmentController {

    private static final Set<String> SORT = Set.of("createdAt");

    private final BalanceAdjustmentService adjustments;

    AdjustmentController(BalanceAdjustmentService adjustments) {
        this.adjustments = adjustments;
    }

    record CreateRequest(
            @NotNull UUID customerId,
            @NotNull @Pattern(regexp = "CREDIT|DEBIT") String direction,
            @NotNull @Pattern(regexp = "^\\d{1,15}(\\.\\d{1,4})?$") String amount,
            @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @NotBlank @Size(min = 5, max = 500) String reason) {}

    record ApprovalRequest(@Size(max = 500) String note) {}

    record RejectionRequest(@NotBlank @Size(min = 5, max = 500) String reason) {}

    record Money(String amount, String currency) {}

    record CustomerRef(UUID id, String displayName) {}

    record AdjustmentResponse(
            UUID id,
            CustomerRef customer,
            String direction,
            Money amount,
            String reason,
            String status,
            String requestedBy,
            String decidedBy,
            Instant decidedAt,
            Instant createdAt) {
        static AdjustmentResponse from(AdjustmentView v) {
            return new AdjustmentResponse(
                    v.id(),
                    new CustomerRef(v.customerId(), v.customerName()),
                    v.direction(),
                    new Money(v.amount().toPlainString(), v.currency()),
                    v.reason(),
                    v.status(),
                    v.requestedBy(),
                    v.decidedBy(),
                    v.decidedAt(),
                    v.createdAt());
        }
    }

    @GetMapping
    PageResponse<AdjustmentResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) @Size(max = 100) String search) {
        PageParams order = PageParams.parse(sort, SORT, "createdAt", false);
        var result =
                adjustments.list(
                        search == null || search.isBlank() ? null : search.strip(),
                        page,
                        size,
                        !order.ascending());
        return Paged.of(
                result.content().stream().map(AdjustmentResponse::from).toList(),
                page,
                size,
                result.total());
    }

    @PostMapping
    ResponseEntity<AdjustmentResponse> request(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 100) String idempotencyKey,
            @Valid @RequestBody CreateRequest body) {
        var created =
                adjustments.request(
                        JwtSubject.userId(jwt),
                        idempotencyKey,
                        new BalanceAdjustmentService.NewAdjustment(
                                body.customerId(),
                                body.direction(),
                                new BigDecimal(body.amount()),
                                body.currency(),
                                body.reason()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AdjustmentResponse.from(created));
    }

    @PostMapping("/{adjustmentId}/approve")
    AdjustmentResponse approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID adjustmentId,
            @Valid @RequestBody(required = false) ApprovalRequest body) {
        return AdjustmentResponse.from(
                adjustments.approve(
                        JwtSubject.userId(jwt), adjustmentId, body == null ? null : body.note()));
    }

    @PostMapping("/{adjustmentId}/reject")
    AdjustmentResponse reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID adjustmentId,
            @Valid @RequestBody RejectionRequest body) {
        return AdjustmentResponse.from(
                adjustments.reject(JwtSubject.userId(jwt), adjustmentId, body.reason()));
    }
}
