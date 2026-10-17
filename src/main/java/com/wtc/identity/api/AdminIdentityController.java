package com.wtc.identity.api;

import com.wtc.identity.application.AuditQueryService;
import com.wtc.identity.application.StaffAdminService;
import com.wtc.identity.application.port.AuditLogRepository;
import com.wtc.identity.application.port.LoginAuditRepository;
import com.wtc.identity.domain.Role;
import com.wtc.identity.domain.User;
import com.wtc.identity.domain.UserStatus;
import com.wtc.shared.web.PageParams;
import com.wtc.shared.web.PageResponse;
import com.wtc.shared.web.Paged;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Administration des comptes du personnel et consultation des journaux. Permissions vérifiées dans
 * les services.
 */
@RestController
@RequestMapping("/api/admin")
class AdminIdentityController {

    private static final Set<String> USER_SORT =
            Set.of("displayName", "email", "createdAt", "lastLoginAt");
    private static final Set<String> AUDIT_SORT = Set.of("occurredAt");

    private final StaffAdminService staff;
    private final AuditQueryService audits;
    private final JsonMapper json;

    AdminIdentityController(StaffAdminService staff, AuditQueryService audits, JsonMapper json) {
        this.staff = staff;
        this.audits = audits;
        this.json = json;
    }

    record StaffUserResponse(
            UUID id,
            String email,
            String displayName,
            List<Role> roles,
            UserStatus status,
            boolean mfaEnabled,
            Instant lastLoginAt) {
        static StaffUserResponse from(User u) {
            return new StaffUserResponse(
                    u.publicId(),
                    u.email(),
                    u.displayName(),
                    u.roles().stream().sorted().toList(),
                    u.status(),
                    u.mfaEnabled(),
                    u.lastLoginAt());
        }
    }

    record CreateStaffUser(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 150) String displayName,
            @NotEmpty Set<Role> roles) {}

    record UpdateStaffUser(
            @Size(min = 1, max = 150) String displayName,
            @Size(min = 1) Set<Role> roles,
            UserStatus status) {
        boolean isEmpty() {
            return displayName == null && roles == null && status == null;
        }
    }

    record LoginAuditResponse(
            long id,
            String email,
            LoginAuditRepository.Outcome outcome,
            String ipAddress,
            String userAgent,
            Instant occurredAt) {}

    record AuditLogResponse(
            long id,
            Instant occurredAt,
            String actor,
            String actorType,
            String action,
            String entityType,
            String entityId,
            JsonNode before,
            JsonNode after,
            String ipAddress,
            String requestId) {}

    @GetMapping("/users")
    PageResponse<StaffUserResponse> listUsers(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) @Size(max = 100) String search) {
        PageParams order = PageParams.parse(sort, USER_SORT, "createdAt", false);
        StaffAdminService.Page result =
                staff.list(search, page, size, order.field(), order.ascending());
        return Paged.of(
                result.content().stream().map(StaffUserResponse::from).toList(),
                page,
                size,
                result.total());
    }

    @PostMapping("/users")
    ResponseEntity<StaffUserResponse> createUser(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateStaffUser body) {
        User created =
                staff.create(Requests.userId(jwt), body.email(), body.displayName(), body.roles());
        return ResponseEntity.status(HttpStatus.CREATED).body(StaffUserResponse.from(created));
    }

    @PatchMapping("/users/{userId}")
    StaffUserResponse updateUser(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateStaffUser body) {
        if (body.isEmpty()) {
            throw new com.wtc.shared.error.DomainException(
                    com.wtc.shared.error.ErrorCode.MALFORMED_REQUEST,
                    "Au moins un champ à modifier est requis.");
        }
        if (body.status() == UserStatus.PENDING_VERIFICATION) {
            throw new com.wtc.shared.error.DomainException(
                    com.wtc.shared.error.ErrorCode.VALIDATION_FAILED,
                    "Statut non autorisé : ACTIVE ou SUSPENDED.");
        }
        return StaffUserResponse.from(
                staff.update(
                        Requests.userId(jwt),
                        userId,
                        new StaffAdminService.Update(
                                body.displayName(), body.roles(), body.status())));
    }

    @PostMapping("/users/{userId}/mfa-reset")
    ResponseEntity<Void> resetMfa(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        staff.resetMfa(Requests.userId(jwt), userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/login-audits")
    PageResponse<LoginAuditResponse> loginAudits(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        PageParams order = PageParams.parse(sort, AUDIT_SORT, "occurredAt", false);
        LoginAuditRepository.EntryPage result = audits.loginAudits(page, size, !order.ascending());
        return Paged.of(
                result.content().stream()
                        .map(
                                e ->
                                        new LoginAuditResponse(
                                                e.id(),
                                                e.email(),
                                                e.outcome(),
                                                e.ipAddress(),
                                                e.userAgent(),
                                                e.occurredAt()))
                        .toList(),
                page,
                size,
                result.total());
    }

    @GetMapping("/audit-logs")
    PageResponse<AuditLogResponse> auditLogs(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) @Size(max = 60) String entityType,
            @RequestParam(required = false) @Size(max = 100) String entityId,
            @RequestParam(required = false) @Size(max = 100) String actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to) {
        PageParams order = PageParams.parse(sort, AUDIT_SORT, "occurredAt", false);
        AuditLogRepository.EntryPage result =
                audits.auditLogs(
                        new AuditLogRepository.Filter(entityType, entityId, actor, from, to),
                        page,
                        size,
                        !order.ascending());
        return Paged.of(
                result.content().stream()
                        .map(
                                e ->
                                        new AuditLogResponse(
                                                e.id(),
                                                e.occurredAt(),
                                                e.actor(),
                                                e.actorType(),
                                                e.action(),
                                                e.entityType(),
                                                e.entityId(),
                                                e.beforeJson() == null
                                                        ? null
                                                        : json.readTree(e.beforeJson()),
                                                e.afterJson() == null
                                                        ? null
                                                        : json.readTree(e.afterJson()),
                                                e.ipAddress(),
                                                e.requestId()))
                        .toList(),
                page,
                size,
                result.total());
    }
}
