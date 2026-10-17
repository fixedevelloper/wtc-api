package com.wtc.support;

import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes de test uniquement : exercent la sécurité et la conversion des erreurs. Hors du périmètre
 * du contrat.
 */
@RestController
@ConditionalOnProperty("wtc.test.probe")
public class ProbeController {

    public record Payload(@NotBlank String name, @Positive BigDecimal amount) {}

    @GetMapping("/api/_probe/authenticated")
    Map<String, String> authenticated() {
        return Map.of("ok", "true");
    }

    @GetMapping("/api/admin/_probe/transfers")
    @PreAuthorize("hasAuthority('TRANSFER_READ')")
    Map<String, String> transfers() {
        return Map.of("ok", "true");
    }

    @GetMapping("/api/admin/_probe/admin-role")
    @PreAuthorize("hasRole('ADMIN')")
    Map<String, String> adminRole() {
        return Map.of("ok", "true");
    }

    @PostMapping("/api/_probe/validate")
    Map<String, String> validate(@Valid @RequestBody Payload payload) {
        return Map.of("ok", "true");
    }

    @GetMapping("/api/_probe/param")
    Map<String, String> param(@RequestParam @Min(1) int limit) {
        return Map.of("limit", String.valueOf(limit));
    }

    @GetMapping("/api/_probe/domain-error")
    void domainError() {
        throw new DomainException(ErrorCode.CONFLICT, "Le transfert est déjà terminé.");
    }

    @GetMapping("/api/_probe/crash")
    void crash() {
        throw new IllegalStateException(
                "détail interne sensible : jdbc:postgresql://db-prod/wtc password=hunter2");
    }
}
