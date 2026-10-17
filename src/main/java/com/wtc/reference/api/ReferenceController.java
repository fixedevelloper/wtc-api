package com.wtc.reference.api;

import com.wtc.reference.application.ReferenceService;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Référentiels publics : pays, villes et exigences de version des clients (opérations du contrat
 * sans authentification).
 */
@RestController
@RequestMapping("/api")
class ReferenceController {

    private static final CacheControl PUBLIC_CACHE =
            CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final ReferenceService service;

    ReferenceController(ReferenceService service) {
        this.service = service;
    }

    @GetMapping("/countries")
    ResponseEntity<List<CountryResponse>> listCountries() {
        return ResponseEntity.ok()
                .cacheControl(PUBLIC_CACHE)
                .body(service.listCountries().stream().map(CountryResponse::from).toList());
    }

    @GetMapping("/countries/{countryCode}/cities")
    ResponseEntity<List<CityResponse>> listCities(@PathVariable String countryCode) {
        return ResponseEntity.ok()
                .cacheControl(PUBLIC_CACHE)
                .body(service.listCities(countryCode).stream().map(CityResponse::from).toList());
    }

    record BankResponse(long id, String name, String shortCode) {}

    record MobileOperatorResponse(
            long id,
            String name,
            String shortCode,
            boolean supportsPayout,
            boolean supportsPayin) {}

    @GetMapping("/countries/{countryCode}/banks")
    List<BankResponse> listBanks(@PathVariable String countryCode) {
        return service.listBanks(countryCode).stream()
                .map(b -> new BankResponse(b.id(), b.name(), b.shortCode()))
                .toList();
    }

    @GetMapping("/countries/{countryCode}/mobile-operators")
    List<MobileOperatorResponse> listMobileOperators(@PathVariable String countryCode) {
        return service.listMobileOperators(countryCode).stream()
                .map(
                        o ->
                                new MobileOperatorResponse(
                                        o.id(),
                                        o.name(),
                                        o.shortCode(),
                                        o.supportsPayout(),
                                        o.supportsPayin()))
                .toList();
    }

    @GetMapping("/meta/client-requirements")
    ResponseEntity<List<ClientRequirementResponse>> getClientRequirements() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic())
                .body(
                        service.clientRequirements().stream()
                                .map(ClientRequirementResponse::from)
                                .toList());
    }
}
