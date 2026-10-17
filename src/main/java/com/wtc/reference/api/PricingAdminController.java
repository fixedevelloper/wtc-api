package com.wtc.reference.api;

import com.wtc.reference.application.ExchangeRateView;
import com.wtc.reference.application.FeeScheduleView;
import com.wtc.reference.application.Page;
import com.wtc.reference.application.PricingAdminService;
import com.wtc.reference.application.PricingZoneView;
import com.wtc.reference.application.SettingView;
import com.wtc.reference.application.SettingsAdminService;
import com.wtc.shared.security.JwtSubject;
import com.wtc.shared.web.PageParams;
import com.wtc.shared.web.PageResponse;
import com.wtc.shared.web.Paged;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Administration des référentiels de tarification (permission {@code REFERENCE_WRITE}, vérifiée
 * dans les services).
 */
@RestController
@RequestMapping("/api/admin")
class PricingAdminController {

    private static final Set<String> RATE_SORT = Set.of("effectiveFrom");

    private final PricingAdminService pricing;
    private final SettingsAdminService settings;
    private final JsonMapper json;

    PricingAdminController(
            PricingAdminService pricing, SettingsAdminService settings, JsonMapper json) {
        this.pricing = pricing;
        this.settings = settings;
        this.json = json;
    }

    record ZoneInput(
            @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String code,
            @NotBlank @Size(max = 120) String name,
            List<@Pattern(regexp = "^[A-Z]{2}$") String> countries) {}

    record FeeScheduleInput(
            @NotNull Long pricingZoneId,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @NotNull @Pattern(regexp = "^\\d{1,15}(\\.\\d{1,4})?$") String minAmount,
            @NotNull @Pattern(regexp = "^\\d{1,15}(\\.\\d{1,4})?$") String maxAmount,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal percent,
            @Pattern(regexp = "^\\d{1,15}(\\.\\d{1,4})?$") String fixedFee,
            @NotNull LocalDate effectiveFrom) {}

    record EndFeeSchedule(@NotNull LocalDate effectiveTo) {}

    record ExchangeRateInput(
            @NotNull @Pattern(regexp = "^[A-Z]{3}$") String baseCurrency,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$") String quoteCurrency,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal rate,
            Instant effectiveFrom) {}

    record SettingInput(@NotNull JsonNode value) {}

    record FeeScheduleResponse(
            long id,
            long pricingZoneId,
            String currency,
            String minAmount,
            String maxAmount,
            BigDecimal percent,
            String fixedFee,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        static FeeScheduleResponse from(FeeScheduleView v) {
            return new FeeScheduleResponse(
                    v.id(),
                    v.pricingZoneId(),
                    v.currency(),
                    v.minAmount().toPlainString(),
                    v.maxAmount().toPlainString(),
                    v.percent().stripTrailingZeros(),
                    v.fixedFee().signum() == 0 ? null : v.fixedFee().toPlainString(),
                    v.effectiveFrom(),
                    v.effectiveTo());
        }
    }

    record ExchangeRateResponse(
            String baseCurrency,
            String quoteCurrency,
            BigDecimal rate,
            Instant effectiveFrom,
            String source) {
        static ExchangeRateResponse from(ExchangeRateView v) {
            return new ExchangeRateResponse(
                    v.baseCurrency(),
                    v.quoteCurrency(),
                    v.rate().stripTrailingZeros(),
                    v.effectiveFrom(),
                    v.source());
        }
    }

    record SettingResponse(String key, JsonNode value, Instant updatedAt) {}

    // ------------------------------------------------------------------ zones

    @GetMapping("/pricing-zones")
    List<PricingZoneView> listZones() {
        return pricing.zones();
    }

    @PostMapping("/pricing-zones")
    ResponseEntity<PricingZoneView> createZone(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ZoneInput body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        pricing.createZone(
                                JwtSubject.userId(jwt),
                                body.code(),
                                body.name(),
                                body.countries()));
    }

    // ------------------------------------------------------------------ barèmes

    @GetMapping("/fee-schedules")
    List<FeeScheduleResponse> listFeeSchedules() {
        return pricing.feeSchedules().stream().map(FeeScheduleResponse::from).toList();
    }

    @PostMapping("/fee-schedules")
    ResponseEntity<FeeScheduleResponse> createFeeSchedule(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FeeScheduleInput body) {
        var created =
                pricing.createFeeSchedule(
                        JwtSubject.userId(jwt),
                        new PricingAdminService.NewFeeSchedule(
                                body.pricingZoneId(),
                                body.currency(),
                                new BigDecimal(body.minAmount()),
                                new BigDecimal(body.maxAmount()),
                                body.percent(),
                                body.fixedFee() == null ? null : new BigDecimal(body.fixedFee()),
                                body.effectiveFrom()));
        return ResponseEntity.status(HttpStatus.CREATED).body(FeeScheduleResponse.from(created));
    }

    @PatchMapping("/fee-schedules/{feeScheduleId}")
    FeeScheduleResponse endFeeSchedule(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long feeScheduleId,
            @Valid @RequestBody EndFeeSchedule body) {
        return FeeScheduleResponse.from(
                pricing.endFeeSchedule(JwtSubject.userId(jwt), feeScheduleId, body.effectiveTo()));
    }

    // ------------------------------------------------------------------ taux

    @GetMapping("/exchange-rates")
    PageResponse<ExchangeRateResponse> listRates(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        PageParams order = PageParams.parse(sort, RATE_SORT, "effectiveFrom", false);
        Page<ExchangeRateView> result = pricing.rates(page, size, !order.ascending());
        return Paged.of(
                result.content().stream().map(ExchangeRateResponse::from).toList(),
                page,
                size,
                result.total());
    }

    @PostMapping("/exchange-rates")
    ResponseEntity<ExchangeRateResponse> createRate(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ExchangeRateInput body) {
        var created =
                pricing.createRate(
                        JwtSubject.userId(jwt),
                        body.baseCurrency(),
                        body.quoteCurrency(),
                        body.rate(),
                        body.effectiveFrom());
        return ResponseEntity.status(HttpStatus.CREATED).body(ExchangeRateResponse.from(created));
    }

    // ------------------------------------------------------------------ paramètres

    @GetMapping("/settings")
    List<SettingResponse> listSettings() {
        return settings.list().stream().map(this::toResponse).toList();
    }

    @PutMapping("/settings/{key}")
    SettingResponse updateSetting(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(max = 100) String key,
            @Valid @RequestBody SettingInput body) {
        return toResponse(settings.update(JwtSubject.userId(jwt), key, body.value()));
    }

    private SettingResponse toResponse(SettingView view) {
        return new SettingResponse(view.key(), json.readTree(view.valueJson()), view.updatedAt());
    }
}
