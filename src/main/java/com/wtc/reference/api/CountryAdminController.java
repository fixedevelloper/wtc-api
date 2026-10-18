package com.wtc.reference.api;

import com.wtc.reference.application.CountryAdminService;
import com.wtc.shared.security.JwtSubject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ajout d'un pays par le back-office (permission {@code REFERENCE_WRITE}, vérifiée dans le
 * service).
 */
@RestController
@RequestMapping("/api/admin")
class CountryAdminController {

    private final CountryAdminService countries;

    CountryAdminController(CountryAdminService countries) {
        this.countries = countries;
    }

    record CountryInput(
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String code,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String iso3,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currencyCode,
            @Pattern(regexp = "^(\\+\\d{1,4})?$") String callingCode,
            @Size(max = 500) String flagUrl,
            Long pricingZoneId,
            Boolean canSend,
            Boolean canReceive) {}

    @PostMapping("/countries")
    ResponseEntity<CountryResponse> createCountry(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CountryInput body) {
        var created =
                countries.create(
                        JwtSubject.userId(jwt),
                        new CountryAdminService.Input(
                                body.code(),
                                body.iso3(),
                                body.name(),
                                body.currencyCode(),
                                body.callingCode(),
                                body.flagUrl(),
                                body.pricingZoneId(),
                                Boolean.TRUE.equals(body.canSend()),
                                Boolean.TRUE.equals(body.canReceive())));
        return ResponseEntity.status(HttpStatus.CREATED).body(CountryResponse.from(created));
    }

    record CityInput(@NotBlank @Size(max = 120) String name) {}

    @PostMapping("/countries/{countryCode}/cities")
    ResponseEntity<CityResponse> addCity(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = "^[A-Za-z]{2}$") String countryCode,
            @Valid @RequestBody CityInput body) {
        var created = countries.addCity(JwtSubject.userId(jwt), countryCode, body.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(CityResponse.from(created));
    }
}
