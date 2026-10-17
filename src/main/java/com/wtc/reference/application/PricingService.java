package com.wtc.reference.application;

import com.wtc.reference.application.port.CountryRepository;
import com.wtc.reference.application.port.PricingRepository;
import com.wtc.reference.domain.Country;
import com.wtc.reference.domain.CurrencyUnits;
import com.wtc.reference.domain.FeeBand;
import com.wtc.reference.domain.FeeCalculator;
import com.wtc.reference.domain.FxConverter;
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
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moteur de tarification : frais par tranche et conversion de devises. Appelé par le module
 * transfert pour les devis et pour figer les montants à la création d'un transfert.
 *
 * <p>Règles : la <b>zone tarifaire est celle du pays de destination</b> (hypothèse 15 : le barème
 * suit le corridor) et la devise du barème est celle du <b>portefeuille source</b> ; les frais sont
 * arrondis demi supérieur, le montant reçu vers le bas ; le taux est le dernier en vigueur, direct
 * ou, à défaut, l'inverse de la paire opposée.
 */
@Service
public class PricingService {

    private final PricingRepository pricing;
    private final CountryRepository countries;
    private final Clock clock;

    PricingService(PricingRepository pricing, CountryRepository countries, Clock clock) {
        this.pricing = pricing;
        this.countries = countries;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PricingQuote quote(
            String sourceCurrency, String destinationCountryCode, BigDecimal sourceAmount) {
        String source = sourceCurrency == null ? null : sourceCurrency.toUpperCase(Locale.ROOT);
        int digits = CurrencyUnits.fractionDigits(source);
        if (sourceAmount == null || sourceAmount.signum() <= 0) {
            throw invalid("sourceAmount", "Positive", "Le montant doit être strictement positif.");
        }
        if (sourceAmount.stripTrailingZeros().scale() > digits) {
            throw invalid(
                    "sourceAmount",
                    "TooManyDecimals",
                    "Cette devise n'admet que " + digits + " décimale(s).");
        }
        Country destination =
                countries
                        .findEnabledByCode(destinationCountryCode.toUpperCase(Locale.ROOT))
                        .filter(Country::canReceive)
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.DESTINATION_NOT_SUPPORTED,
                                                "Ce pays ne peut pas recevoir de transferts."));

        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        long zone =
                pricing.zoneIdOfCountry(destination.code())
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.FEE_NOT_CONFIGURED,
                                                "Aucune zone tarifaire pour "
                                                        + destination.code()
                                                        + "."));
        FeeBand band =
                FeeCalculator.bandFor(pricing.activeBands(zone, source, today), sourceAmount)
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.FEE_NOT_CONFIGURED,
                                                "Aucun barème de frais ne couvre ce montant en "
                                                        + source
                                                        + "."));
        BigDecimal fee = FeeCalculator.feeFor(sourceAmount, band, source);

        String target = destination.currencyCode();
        BigDecimal rate =
                FxConverter.rate(
                                source,
                                target,
                                pricing.latestRate(source, target, now),
                                pricing.latestRate(target, source, now))
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.EXCHANGE_RATE_UNAVAILABLE,
                                                "Aucun taux " + source + " → " + target + "."));
        BigDecimal received = FxConverter.convert(sourceAmount, rate, target);
        if (received.signum() <= 0) {
            throw invalid(
                    "sourceAmount",
                    "TooSmall",
                    "Montant trop faible : le bénéficiaire ne recevrait rien.");
        }
        BigDecimal scaledAmount =
                CurrencyUnits.round(sourceAmount, source, java.math.RoundingMode.UNNECESSARY);
        return new PricingQuote(
                source,
                scaledAmount,
                fee,
                band.percent(),
                scaledAmount.add(fee),
                target,
                received,
                rate,
                band.id());
    }

    /** Devise du pays donné (pour choisir la devise de destination d'un transfert). */
    @Transactional(readOnly = true)
    public Optional<String> currencyOf(String countryCode) {
        return countries
                .findEnabledByCode(countryCode.toUpperCase(Locale.ROOT))
                .map(Country::currencyCode);
    }

    private static DomainException invalid(String field, String code, String message) {
        return new DomainException(
                ErrorCode.VALIDATION_FAILED,
                message,
                List.of(new FieldViolation(field, code, message)));
    }
}
