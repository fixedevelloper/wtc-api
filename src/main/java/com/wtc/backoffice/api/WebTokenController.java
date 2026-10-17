package com.wtc.backoffice.api;

import com.wtc.backoffice.application.WebTokenService;
import com.wtc.backoffice.application.port.WebTokenRepository.WebTokenRecord;
import com.wtc.shared.security.JwtSubject;
import com.wtc.shared.web.PageParams;
import com.wtc.shared.web.PageResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Jetons web : lecture et consommation par le chatbot (clé de service), gestion par le personnel.
 */
@RestController
@RequestMapping("/api")
@Validated
class WebTokenController {

    private static final Set<String> SORT = Set.of("createdAt", "expiresAt", "status", "amount");

    private final WebTokenService service;

    WebTokenController(WebTokenService service) {
        this.service = service;
    }

    record Money(String amount, String currency) {}

    record WebTokenView(Money amount, String countryCode, Instant expiresAt, String status) {}

    record AdminWebTokenView(
            long id,
            Money amount,
            String countryCode,
            String status,
            Instant expiresAt,
            Instant usedAt,
            Instant createdAt) {}

    record CreateRequest(
            @NotNull @Pattern(regexp = "^\\d{1,15}(\\.\\d{1,4})?$") String amount,
            @NotNull @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
            @Min(1) @Max(1440) Integer validForMinutes) {}

    record CreatedView(String token, Instant expiresAt) {}

    record ConsumeRequest(@NotBlank @Size(max = 64) String whatsappId) {}

    @GetMapping("/web-tokens/{token}")
    WebTokenView inspect(@PathVariable String token) {
        WebTokenRecord r = service.inspect(token);
        return new WebTokenView(money(r), r.countryCode(), r.expiresAt(), r.status());
    }

    @PostMapping("/web-tokens/{token}/consume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void consume(@PathVariable String token, @Valid @RequestBody ConsumeRequest request) {
        service.consume(token, request.whatsappId());
    }

    @GetMapping("/admin/web-tokens")
    PageResponse<AdminWebTokenView> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        PageParams order = PageParams.parse(sort, SORT, "createdAt", false);
        PageResponse<WebTokenRecord> result =
                service.list(page, size, order.field(), order.ascending());
        return new PageResponse<>(
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages(),
                result.content().stream()
                        .map(
                                r ->
                                        new AdminWebTokenView(
                                                r.id(),
                                                money(r),
                                                r.countryCode(),
                                                r.status(),
                                                r.expiresAt(),
                                                r.usedAt(),
                                                r.createdAt()))
                        .toList());
    }

    @PostMapping("/admin/web-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    CreatedView create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest request) {
        var created =
                service.create(
                        JwtSubject.userId(jwt),
                        new BigDecimal(request.amount()),
                        request.countryCode(),
                        request.validForMinutes() == null ? 60 : request.validForMinutes());
        return new CreatedView(created.token(), created.expiresAt());
    }

    private static Money money(WebTokenRecord r) {
        int digits =
                Math.max(
                        java.util.Currency.getInstance(r.currency()).getDefaultFractionDigits(), 0);
        // Jamais d'arrondi : au moins les décimales de la devise, davantage si le montant en a.
        BigDecimal exact = r.amount().stripTrailingZeros();
        return new Money(
                exact.setScale(Math.max(digits, Math.max(exact.scale(), 0))).toPlainString(),
                r.currency());
    }
}
