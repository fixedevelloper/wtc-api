package com.wtc.reference.infrastructure;

import com.wtc.reference.application.ExchangeRateView;
import com.wtc.reference.application.FeeScheduleView;
import com.wtc.reference.application.Page;
import com.wtc.reference.application.PricingZoneView;
import com.wtc.reference.application.port.PricingRepository;
import com.wtc.reference.domain.FeeBand;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPricingRepository implements PricingRepository {

    private static final RowMapper<FeeScheduleView> SCHEDULE =
            (rs, n) ->
                    new FeeScheduleView(
                            rs.getLong("id"),
                            rs.getLong("pricing_zone_id"),
                            rs.getString("currency_code"),
                            rs.getBigDecimal("min_amount"),
                            rs.getBigDecimal("max_amount"),
                            rs.getBigDecimal("percent"),
                            rs.getBigDecimal("fixed_fee"),
                            rs.getObject("effective_from", LocalDate.class),
                            rs.getObject("effective_to", LocalDate.class));

    private static final String SCHEDULE_SELECT =
            "SELECT id, pricing_zone_id, currency_code, min_amount, max_amount, percent, fixed_fee,"
                    + " effective_from, effective_to FROM fee_schedules";

    private final JdbcClient jdbc;

    JdbcPricingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> zoneIdOfCountry(String countryCode) {
        return jdbc.sql(
                        "SELECT pricing_zone_id FROM countries WHERE code = :c AND pricing_zone_id"
                                + " IS NOT NULL")
                .param("c", countryCode)
                .query(Long.class)
                .optional();
    }

    @Override
    public List<FeeBand> activeBands(long zoneId, String currency, LocalDate on) {
        return jdbc.sql(
                        """
                        SELECT id, min_amount, max_amount, percent, fixed_fee FROM fee_schedules
                        WHERE pricing_zone_id = :z AND currency_code = :c AND effective_from <= :d
                          AND (effective_to IS NULL OR effective_to > :d)
                        ORDER BY min_amount
                        """)
                .param("z", zoneId)
                .param("c", currency)
                .param("d", Date.valueOf(on))
                .query(
                        (rs, n) ->
                                new FeeBand(
                                        rs.getLong("id"),
                                        rs.getBigDecimal("min_amount"),
                                        rs.getBigDecimal("max_amount"),
                                        rs.getBigDecimal("percent"),
                                        rs.getBigDecimal("fixed_fee")))
                .list();
    }

    @Override
    public Optional<BigDecimal> latestRate(String base, String quote, Instant at) {
        return jdbc.sql(
                        """
                        SELECT rate FROM exchange_rates WHERE base_currency = :b AND quote_currency = :q AND effective_from <= :at
                        ORDER BY effective_from DESC LIMIT 1
                        """)
                .param("b", base)
                .param("q", quote)
                .param("at", Timestamp.from(at))
                .query(BigDecimal.class)
                .optional();
    }

    @Override
    public List<PricingZoneView> listZones() {
        List<PricingZoneView> zones = new ArrayList<>();
        jdbc.sql("SELECT id, code, name FROM pricing_zones ORDER BY code")
                .query(
                        (rs, n) -> {
                            long id = rs.getLong("id");
                            List<String> countries =
                                    jdbc.sql(
                                                    "SELECT code FROM countries WHERE"
                                                            + " pricing_zone_id = :z ORDER BY code")
                                            .param("z", id)
                                            .query(String.class)
                                            .list();
                            return new PricingZoneView(
                                    id, rs.getString("code"), rs.getString("name"), countries);
                        })
                .list()
                .forEach(zones::add);
        return zones;
    }

    @Override
    public boolean zoneCodeExists(String code) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM pricing_zones WHERE lower(code) = lower(:c))")
                .param("c", code)
                .query(Boolean.class)
                .single();
    }

    @Override
    public long insertZone(String code, String name) {
        return jdbc.sql("INSERT INTO pricing_zones (code, name) VALUES (:c, :n) RETURNING id")
                .param("c", code)
                .param("n", name)
                .query(Long.class)
                .single();
    }

    @Override
    public int assignCountries(long zoneId, List<String> countryCodes) {
        if (countryCodes.isEmpty()) {
            return 0;
        }
        return jdbc.sql("UPDATE countries SET pricing_zone_id = :z WHERE code IN (:codes)")
                .param("z", zoneId)
                .param("codes", countryCodes)
                .update();
    }

    @Override
    public boolean zoneExists(long zoneId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM pricing_zones WHERE id = :id)")
                .param("id", zoneId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public List<FeeScheduleView> listFeeSchedules() {
        return jdbc.sql(
                        SCHEDULE_SELECT
                                + " ORDER BY pricing_zone_id, currency_code, effective_from DESC,"
                                + " min_amount")
                .query(SCHEDULE)
                .list();
    }

    @Override
    public Optional<FeeScheduleView> findFeeSchedule(long id) {
        return jdbc.sql(SCHEDULE_SELECT + " WHERE id = :id")
                .param("id", id)
                .query(SCHEDULE)
                .optional();
    }

    @Override
    public long insertFeeSchedule(
            long zoneId,
            String currency,
            BigDecimal min,
            BigDecimal max,
            BigDecimal percent,
            BigDecimal fixedFee,
            LocalDate from,
            Long createdByUser) {
        return jdbc.sql(
                        """
                        INSERT INTO fee_schedules (pricing_zone_id, currency_code, min_amount, max_amount, percent, fixed_fee, effective_from, created_by)
                        VALUES (:z, :c, :min, :max, :p, :f, :from, :by) RETURNING id
                        """)
                .param("z", zoneId)
                .param("c", currency)
                .param("min", min)
                .param("max", max)
                .param("p", percent)
                .param("f", fixedFee)
                .param("from", Date.valueOf(from))
                .param("by", createdByUser)
                .query(Long.class)
                .single();
    }

    @Override
    public void endFeeSchedule(long id, LocalDate effectiveTo) {
        jdbc.sql("UPDATE fee_schedules SET effective_to = :to WHERE id = :id")
                .param("to", Date.valueOf(effectiveTo))
                .param("id", id)
                .update();
    }

    @Override
    public Page<ExchangeRateView> listRates(int page, int size, boolean newestFirst) {
        String order = newestFirst ? "DESC" : "ASC";
        List<ExchangeRateView> rows =
                jdbc.sql(
                                "SELECT base_currency, quote_currency, rate, effective_from, source"
                                        + " FROM exchange_rates ORDER BY effective_from "
                                        + order
                                        + ", id "
                                        + order
                                        + " LIMIT :size OFFSET :offset")
                        .param("size", size)
                        .param("offset", (long) page * size)
                        .query(
                                (rs, n) ->
                                        new ExchangeRateView(
                                                rs.getString("base_currency"),
                                                rs.getString("quote_currency"),
                                                rs.getBigDecimal("rate"),
                                                rs.getTimestamp("effective_from").toInstant(),
                                                rs.getString("source")))
                        .list();
        return new Page<>(
                rows, jdbc.sql("SELECT count(*) FROM exchange_rates").query(Long.class).single());
    }

    @Override
    public void insertRate(
            String base, String quote, BigDecimal rate, Instant effectiveFrom, String source) {
        jdbc.sql(
                        "INSERT INTO exchange_rates (base_currency, quote_currency, rate,"
                                + " effective_from, source) VALUES (:b, :q, :r, :at, :s)")
                .param("b", base)
                .param("q", quote)
                .param("r", rate)
                .param("at", Timestamp.from(effectiveFrom))
                .param("s", source)
                .update();
    }
}
