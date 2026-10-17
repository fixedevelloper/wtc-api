package com.wtc.backoffice.application;

import com.wtc.backoffice.application.port.WebTokenRepository;
import com.wtc.backoffice.application.port.WebTokenRepository.WebTokenRecord;
import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import com.wtc.shared.security.Tokens;
import com.wtc.shared.web.PageResponse;
import com.wtc.shared.web.Paged;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Jetons à usage unique qui pré-remplissent un transfert (montant, pays) dans le canal WhatsApp.
 * Seule l'empreinte est stockée : le jeton n'est montré qu'à sa création. La consommation
 * verrouille la ligne : deux appels simultanés ne peuvent pas tous deux réussir.
 */
@Service
public class WebTokenService {

    public record Created(String token, Instant expiresAt) {}

    private final WebTokenRepository tokens;
    private final AuditTrail audit;
    private final Clock clock;

    WebTokenService(WebTokenRepository tokens, AuditTrail audit, Clock clock) {
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority('TRANSFER_CREATE')")
    @Transactional
    public Created create(UUID actor, BigDecimal amount, String countryCode, int validForMinutes) {
        if (amount.signum() <= 0 || amount.scale() > 4) {
            throw invalid("amount", "Le montant doit être positif, 4 décimales au plus.");
        }
        if (validForMinutes < 1 || validForMinutes > 1440) {
            throw invalid("validForMinutes", "La validité va de 1 à 1440 minutes.");
        }
        if (tokens.receivingCurrency(countryCode).isEmpty()) {
            throw new DomainException(
                    ErrorCode.COUNTRY_NOT_FOUND,
                    "Pays inconnu ou qui ne reçoit pas de transferts.");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofMinutes(validForMinutes));
        String token = Tokens.random();
        tokens.insert(Tokens.sha256(token), amount, countryCode, expiresAt, actor, now);
        audit.recordAs(actor, "WEB_TOKEN_CREATED", "WEB_TOKEN", countryCode, null, null);
        return new Created(token, expiresAt);
    }

    /**
     * Lecture par le chatbot : un jeton expiré est présenté comme tel même avant le passage du
     * nettoyage.
     */
    @Transactional(readOnly = true)
    public WebTokenRecord inspect(String token) {
        WebTokenRecord record =
                tokens.findByHash(Tokens.sha256(token)).orElseThrow(WebTokenService::notFound);
        return effective(record);
    }

    @Transactional
    public void consume(String token, String whatsappId) {
        if (whatsappId == null || whatsappId.isBlank() || whatsappId.length() > 64) {
            throw invalid(
                    "whatsappId", "Identifiant WhatsApp obligatoire (64 caractères au plus).");
        }
        WebTokenRecord record =
                tokens.lockByHash(Tokens.sha256(token)).orElseThrow(WebTokenService::notFound);
        if (!"PENDING".equals(effective(record).status())) {
            throw new DomainException(
                    ErrorCode.WEB_TOKEN_UNAVAILABLE, "Ce jeton est déjà utilisé ou expiré.");
        }
        tokens.markUsed(record.id(), whatsappId.strip(), clock.instant());
    }

    @PreAuthorize("hasAuthority('TRANSFER_CREATE')")
    @Transactional(readOnly = true)
    public PageResponse<WebTokenRecord> list(
            int page, int size, String sortField, boolean ascending) {
        return Paged.of(
                tokens.page(page, size, sortField, ascending).stream()
                        .map(this::effective)
                        .toList(),
                page,
                size,
                tokens.count());
    }

    /** Tâche planifiée : met à jour le statut des jetons arrivés à échéance. */
    @Transactional
    public int expireDue() {
        return tokens.expireDue(clock.instant());
    }

    private WebTokenRecord effective(WebTokenRecord r) {
        if ("PENDING".equals(r.status()) && !r.expiresAt().isAfter(clock.instant())) {
            return new WebTokenRecord(
                    r.id(),
                    r.amount(),
                    r.currency(),
                    r.countryCode(),
                    "EXPIRED",
                    r.expiresAt(),
                    r.usedAt(),
                    r.createdAt());
        }
        return r;
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.WEB_TOKEN_NOT_FOUND, "Jeton introuvable.");
    }

    private static DomainException invalid(String field, String message) {
        return new DomainException(
                ErrorCode.VALIDATION_FAILED,
                message,
                List.of(new FieldViolation(field, "Invalid", message)));
    }
}
