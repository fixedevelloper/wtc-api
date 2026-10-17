package com.wtc.reference.application.port;

import com.wtc.reference.application.ExchangeRateView;
import com.wtc.reference.application.FeeScheduleView;
import com.wtc.reference.application.Page;
import com.wtc.reference.application.PricingZoneView;
import com.wtc.reference.domain.FeeBand;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PricingRepository {

    // lecture pour le calcul
    Optional<Long> zoneIdOfCountry(String countryCode);

    List<FeeBand> activeBands(long zoneId, String currency, LocalDate on);

    Optional<BigDecimal> latestRate(String base, String quote, Instant at);

    // administration
    List<PricingZoneView> listZones();

    boolean zoneCodeExists(String code);

    /**
     * @return identifiant de la zone créée
     */
    long insertZone(String code, String name);

    /** Rattache les pays à la zone ; retourne le nombre de pays réellement trouvés. */
    int assignCountries(long zoneId, List<String> countryCodes);

    boolean zoneExists(long zoneId);

    List<FeeScheduleView> listFeeSchedules();

    Optional<FeeScheduleView> findFeeSchedule(long id);

    /**
     * @throws org.springframework.dao.DataIntegrityViolationException si la tranche chevauche une
     *     autre (contrainte d'exclusion)
     */
    long insertFeeSchedule(
            long zoneId,
            String currency,
            BigDecimal min,
            BigDecimal max,
            BigDecimal percent,
            BigDecimal fixedFee,
            LocalDate from,
            Long createdByUser);

    void endFeeSchedule(long id, LocalDate effectiveTo);

    Page<ExchangeRateView> listRates(int page, int size, boolean newestFirst);

    /**
     * @throws org.springframework.dao.DuplicateKeyException si la paire existe déjà à cet instant
     */
    void insertRate(
            String base, String quote, BigDecimal rate, Instant effectiveFrom, String source);
}
