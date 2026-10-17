package com.wtc.reference.application;

import com.wtc.reference.application.port.PricingRepository;
import com.wtc.reference.domain.CurrencyUnits;
import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration des zones, barèmes et taux (permission {@code REFERENCE_WRITE}). Toute écriture
 * est auditée.
 */
@Service
public class PricingAdminService {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999.9999");

    private final PricingRepository pricing;
    private final AuditTrail audit;
    private final Clock clock;

    PricingAdminService(PricingRepository pricing, AuditTrail audit, Clock clock) {
        this.pricing = pricing;
        this.audit = audit;
        this.clock = clock;
    }

    public record NewFeeSchedule(
            long pricingZoneId,
            String currency,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            BigDecimal percent,
            BigDecimal fixedFee,
            LocalDate effectiveFrom) {}

    // ------------------------------------------------------------------ zones

    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    @Transactional(readOnly = true)
    public List<PricingZoneView> zones() {
        return pricing.listZones();
    }

    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    @Transactional
    public PricingZoneView createZone(
            UUID actor, String code, String name, List<String> countryCodes) {
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        if (pricing.zoneCodeExists(normalized)) {
            throw new DomainException(
                    ErrorCode.PRICING_ZONE_EXISTS, "Ce code de zone est déjà utilisé.");
        }
        List<String> countries =
                countryCodes == null ? List.of() : countryCodes.stream().distinct().toList();
        long id = pricing.insertZone(normalized, name.strip());
        if (pricing.assignCountries(id, countries) != countries.size()) {
            throw invalid("countries", "UnknownCountry", "Au moins un pays est inconnu.");
        }
        audit.recordAs(
                actor,
                "PRICING_ZONE_CREATED",
                "PRICING_ZONE",
                String.valueOf(id),
                null,
                "{\"code\":\"" + normalized + "\"}");
        return pricing.listZones().stream().filter(z -> z.id() == id).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ barèmes

    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    @Transactional(readOnly = true)
    public List<FeeScheduleView> feeSchedules() {
        return pricing.listFeeSchedules();
    }

    /**
     * Crée une tranche. Elle ne peut pas commencer dans le passé (on ne réécrit pas l'histoire des
     * devis) et ne doit pas chevaucher une tranche en vigueur sur la même période : la base
     * l'interdit par une contrainte d'exclusion.
     */
    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    @Transactional
    public FeeScheduleView createFeeSchedule(UUID actor, NewFeeSchedule n) {
        String currency = n.currency().toUpperCase(Locale.ROOT);
        CurrencyUnits.fractionDigits(currency);
        if (!pricing.zoneExists(n.pricingZoneId())) {
            throw invalid("pricingZoneId", "Unknown", "Zone tarifaire inconnue.");
        }
        if (n.minAmount().signum() < 0
                || n.maxAmount().compareTo(n.minAmount()) <= 0
                || n.maxAmount().compareTo(MAX_AMOUNT) > 0) {
            throw invalid("maxAmount", "InvalidRange", "Il faut 0 ≤ min < max.");
        }
        if (n.percent().signum() < 0
                || n.percent().compareTo(BigDecimal.valueOf(100)) > 0
                || n.percent().scale() > 4) {
            throw invalid(
                    "percent",
                    "InvalidPercent",
                    "Pourcentage entre 0 et 100, 4 décimales au plus.");
        }
        BigDecimal fixed = n.fixedFee() == null ? BigDecimal.ZERO : n.fixedFee();
        if (fixed.signum() < 0) {
            throw invalid("fixedFee", "Negative", "Le frais fixe ne peut pas être négatif.");
        }
        if (n.effectiveFrom().isBefore(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))) {
            throw invalid(
                    "effectiveFrom",
                    "InPast",
                    "Une tranche ne peut pas prendre effet dans le passé.");
        }
        long id;
        try {
            id =
                    pricing.insertFeeSchedule(
                            n.pricingZoneId(),
                            currency,
                            n.minAmount(),
                            n.maxAmount(),
                            n.percent(),
                            fixed,
                            n.effectiveFrom(),
                            null);
        } catch (DataIntegrityViolationException overlap) {
            throw new DomainException(
                    ErrorCode.FEE_SCHEDULE_OVERLAP,
                    "Cette tranche chevauche une tranche existante pour la même zone, devise et"
                            + " période.");
        }
        audit.recordAs(
                actor,
                "FEE_SCHEDULE_CREATED",
                "FEE_SCHEDULE",
                String.valueOf(id),
                null,
                "{\"zone\":"
                        + n.pricingZoneId()
                        + ",\"currency\":\""
                        + currency
                        + "\",\"percent\":"
                        + n.percent().toPlainString()
                        + "}");
        return pricing.findFeeSchedule(id).orElseThrow();
    }

    /**
     * Met fin à une tranche (jamais de suppression : les transferts passés référencent leur
     * tranche).
     */
    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    @Transactional
    public FeeScheduleView endFeeSchedule(UUID actor, long id, LocalDate effectiveTo) {
        FeeScheduleView current =
                pricing.findFeeSchedule(id)
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.FEE_SCHEDULE_NOT_FOUND,
                                                "Tranche introuvable."));
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (!effectiveTo.isAfter(current.effectiveFrom())) {
            throw invalid("effectiveTo", "BeforeStart", "La fin doit suivre le début.");
        }
        if (effectiveTo.isBefore(today)) {
            throw invalid(
                    "effectiveTo", "InPast", "On ne peut pas terminer une tranche dans le passé.");
        }
        if (current.effectiveTo() != null && !effectiveTo.isBefore(current.effectiveTo())) {
            throw invalid(
                    "effectiveTo", "NotEarlier", "La nouvelle fin doit précéder la fin actuelle.");
        }
        pricing.endFeeSchedule(id, effectiveTo);
        audit.recordAs(
                actor,
                "FEE_SCHEDULE_ENDED",
                "FEE_SCHEDULE",
                String.valueOf(id),
                null,
                "{\"effectiveTo\":\"" + effectiveTo + "\"}");
        return pricing.findFeeSchedule(id).orElseThrow();
    }

    // ------------------------------------------------------------------ taux de change

    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    @Transactional(readOnly = true)
    public Page<ExchangeRateView> rates(int page, int size, boolean newestFirst) {
        return pricing.listRates(page, size, newestFirst);
    }

    /**
     * Enregistre un taux (jamais de modification : un nouveau taux prend le relais à sa date
     * d'effet). Les taux doivent suivre ceux de Wace (voir wace-integration.md) : un écart se paie
     * à chaque transfert.
     */
    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    @Transactional
    public ExchangeRateView createRate(
            UUID actor, String base, String quote, BigDecimal rate, Instant effectiveFrom) {
        String b = base.toUpperCase(Locale.ROOT);
        String q = quote.toUpperCase(Locale.ROOT);
        CurrencyUnits.fractionDigits(b);
        CurrencyUnits.fractionDigits(q);
        if (b.equals(q)) {
            throw invalid("quoteCurrency", "SameCurrency", "Les deux devises doivent différer.");
        }
        if (rate.signum() <= 0 || rate.precision() - rate.scale() > 14 || rate.scale() > 10) {
            throw invalid("rate", "InvalidRate", "Taux strictement positif, 10 décimales au plus.");
        }
        Instant at = effectiveFrom == null ? clock.instant() : effectiveFrom;
        try {
            pricing.insertRate(b, q, rate, at, "MANUAL");
        } catch (DuplicateKeyException duplicate) {
            throw new DomainException(
                    ErrorCode.EXCHANGE_RATE_EXISTS,
                    "Un taux existe déjà pour cette paire à cette date.");
        }
        audit.recordAs(
                actor,
                "EXCHANGE_RATE_CREATED",
                "EXCHANGE_RATE",
                b + "/" + q,
                null,
                "{\"rate\":" + rate.toPlainString() + "}");
        return new ExchangeRateView(b, q, rate, at, "MANUAL");
    }

    private static DomainException invalid(String field, String code, String message) {
        return new DomainException(
                ErrorCode.VALIDATION_FAILED,
                message,
                List.of(new FieldViolation(field, code, message)));
    }
}
