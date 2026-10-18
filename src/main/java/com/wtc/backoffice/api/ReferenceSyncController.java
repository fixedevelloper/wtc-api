package com.wtc.backoffice.api;

import com.wtc.backoffice.application.ReferenceSyncService;
import com.wtc.shared.security.JwtSubject;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Import des référentiels depuis Wace (permission {@code REFERENCE_WRITE}, vérifiée dans le
 * service).
 */
@RestController
@RequestMapping("/api/admin/reference")
class ReferenceSyncController {

    private final ReferenceSyncService sync;

    ReferenceSyncController(ReferenceSyncService sync) {
        this.sync = sync;
    }

    record SyncResult(
            int banks,
            int operators,
            int payerCodes,
            int referenceValues,
            Instant startedAt,
            List<String> warnings) {}

    @PostMapping("/sync")
    SyncResult sync(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Za-z]{2}$") String countryCode) {
        var report = sync.sync(JwtSubject.userId(jwt), countryCode);
        return new SyncResult(
                report.banks(),
                report.operators(),
                report.payerCodes(),
                report.referenceValues(),
                report.startedAt(),
                report.warnings());
    }
}
