package com.wtc.backoffice.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebTokenRepository {

    record WebTokenRecord(
            long id,
            BigDecimal amount,
            String currency,
            String countryCode,
            String status,
            Instant expiresAt,
            Instant usedAt,
            Instant createdAt) {}

    /** Devise du pays s'il peut recevoir des transferts. */
    Optional<String> receivingCurrency(String countryCode);

    void insert(
            String tokenHash,
            BigDecimal amount,
            String countryCode,
            Instant expiresAt,
            UUID createdBy,
            Instant now);

    /** Lecture simple par empreinte. */
    Optional<WebTokenRecord> findByHash(String tokenHash);

    /** Lecture avec verrou de la ligne, pour une consommation sans course. */
    Optional<WebTokenRecord> lockByHash(String tokenHash);

    void markUsed(long id, String whatsappId, Instant now);

    List<WebTokenRecord> page(int page, int size, String sortField, boolean ascending);

    long count();

    /** Passe en EXPIRED les jetons en attente dont l'échéance est passée. */
    int expireDue(Instant now);
}
