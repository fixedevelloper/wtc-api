package com.wtc.wallet.api;

import com.wtc.shared.web.PageParams;
import com.wtc.shared.web.PageResponse;
import com.wtc.shared.web.Paged;
import com.wtc.wallet.application.TopUpAdminService;
import com.wtc.wallet.application.TopUpView;
import com.wtc.wallet.application.port.TopUpRepository.Filter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Liste des recharges de portefeuille pour le back-office (permission {@code TOPUP_REVIEW}). */
@RestController
@RequestMapping("/api/admin/top-ups")
class TopUpAdminController {

    private static final Set<String> SORT = Set.of("createdAt");

    private final TopUpAdminService topUps;

    TopUpAdminController(TopUpAdminService topUps) {
        this.topUps = topUps;
    }

    record Money(String amount, String currency) {}

    record CustomerRef(UUID id, String displayName) {}

    record AdminTopUp(
            UUID id,
            String reference,
            String method,
            Money amount,
            String status,
            String failureReason,
            Instant createdAt,
            Instant completedAt,
            CustomerRef customer) {
        static AdminTopUp from(TopUpView v) {
            return new AdminTopUp(
                    v.id(),
                    v.reference(),
                    v.method(),
                    new Money(v.amount().toPlainString(), v.currency()),
                    v.status(),
                    v.failureReason(),
                    v.createdAt(),
                    v.completedAt(),
                    new CustomerRef(v.customerId(), v.customerName()));
        }
    }

    @GetMapping
    PageResponse<AdminTopUp> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) @Size(max = 100) String search,
            @RequestParam(required = false)
                    @Pattern(regexp = "PENDING|PROCESSING|COMPLETED|FAILED|CANCELLED|REJECTED")
                    String status,
            @RequestParam(required = false) UUID customerId) {
        PageParams order = PageParams.parse(sort, SORT, "createdAt", false);
        var result =
                topUps.search(
                        new Filter(
                                status,
                                customerId,
                                null,
                                null,
                                search == null || search.isBlank() ? null : search.strip()),
                        page,
                        size,
                        !order.ascending());
        return Paged.of(
                result.content().stream().map(AdminTopUp::from).toList(),
                page,
                size,
                result.total());
    }
}
