package com.wtc.reference.infrastructure;

import com.wtc.reference.application.port.PayoutCatalogRepository;
import com.wtc.reference.domain.Bank;
import com.wtc.reference.domain.MobileOperator;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPayoutCatalogRepository implements PayoutCatalogRepository {

    private static final RowMapper<Bank> BANK =
            (rs, n) ->
                    new Bank(
                            rs.getLong("id"),
                            rs.getString("country_code"),
                            rs.getString("name"),
                            rs.getString("short_code"));
    private static final RowMapper<MobileOperator> OPERATOR =
            (rs, n) ->
                    new MobileOperator(
                            rs.getLong("id"),
                            rs.getString("country_code"),
                            rs.getString("name"),
                            rs.getString("short_code"),
                            rs.getBoolean("supports_payout"),
                            rs.getBoolean("supports_payin"));

    private final JdbcClient jdbc;

    JdbcPayoutCatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Bank> findEnabledBanks(String countryCode) {
        return jdbc.sql(
                        "SELECT id, country_code, name, short_code FROM banks WHERE country_code ="
                                + " :c AND enabled ORDER BY name")
                .param("c", countryCode)
                .query(BANK)
                .list();
    }

    @Override
    public List<MobileOperator> findEnabledOperators(String countryCode) {
        return jdbc.sql(
                        "SELECT id, country_code, name, short_code, supports_payout, supports_payin"
                            + " FROM mobile_operators WHERE country_code = :c AND enabled ORDER BY"
                            + " name")
                .param("c", countryCode)
                .query(OPERATOR)
                .list();
    }

    @Override
    public Optional<Bank> findEnabledBank(long id) {
        return jdbc.sql(
                        "SELECT id, country_code, name, short_code FROM banks WHERE id = :id AND"
                                + " enabled")
                .param("id", id)
                .query(BANK)
                .optional();
    }

    @Override
    public java.util.List<SyncCountry> receivingCountries() {
        return jdbc.sql(
                        "SELECT code, currency_code FROM countries WHERE enabled AND can_receive"
                                + " ORDER BY code")
                .query(
                        (rs, n) ->
                                new SyncCountry(
                                        rs.getString("code").strip(),
                                        rs.getString("currency_code").strip()))
                .list();
    }

    @Override
    public java.util.List<OperatorRef> operatorsOf(String countryCode) {
        return jdbc.sql(
                        "SELECT id, short_code, name FROM mobile_operators WHERE country_code = :c"
                                + " AND enabled ORDER BY id")
                .param("c", countryCode)
                .query(
                        (rs, n) ->
                                new OperatorRef(
                                        rs.getLong("id"),
                                        rs.getString("short_code"),
                                        rs.getString("name")))
                .list();
    }

    @Override
    public void setBankPayerCode(String countryCode, String payerCode) {
        jdbc.sql("UPDATE countries SET wace_bank_payer_code = :p WHERE code = :c")
                .param("p", payerCode)
                .param("c", countryCode)
                .update();
    }

    @Override
    public void setOperatorPayerCode(long operatorId, String payerCode) {
        jdbc.sql("UPDATE mobile_operators SET wace_payer_code = :p WHERE id = :id")
                .param("p", payerCode)
                .param("id", operatorId)
                .update();
    }

    @Override
    public void upsertBank(String countryCode, long waceBankId, String bankCode, String name) {
        jdbc.sql(
                        """
                        INSERT INTO banks (country_code, name, short_code, wace_bank_id) VALUES (:c, :n, :code, CAST(:w AS text))
                        ON CONFLICT (country_code, wace_bank_id) DO UPDATE SET name = EXCLUDED.name, short_code = EXCLUDED.short_code
                        """)
                .param("c", countryCode)
                .param("n", name)
                .param("code", bankCode)
                .param("w", waceBankId)
                .update();
    }

    @Override
    public void upsertReferenceValue(
            String category, String partyTypePair, String code, String label, String waceCode) {
        jdbc.sql(
                        """
                        INSERT INTO reference_values (category, party_type_pair, code, label, wace_code) VALUES (:cat, :pair, :code, :label, :wace)
                        ON CONFLICT (category, party_type_pair, code) DO UPDATE SET label = EXCLUDED.label, wace_code = EXCLUDED.wace_code
                        """)
                .param("cat", category)
                .param("pair", partyTypePair)
                .param("code", code)
                .param("label", label)
                .param("wace", waceCode)
                .update();
    }

    @Override
    public Optional<String> waceBankId(long bankId) {
        return jdbc.sql(
                        "SELECT wace_bank_id FROM banks WHERE id = :id AND wace_bank_id IS NOT"
                                + " NULL")
                .param("id", bankId)
                .query(String.class)
                .optional();
    }

    @Override
    public Optional<String> operatorPayerCode(long operatorId) {
        return jdbc.sql(
                        "SELECT wace_payer_code FROM mobile_operators WHERE id = :id AND"
                                + " wace_payer_code IS NOT NULL")
                .param("id", operatorId)
                .query(String.class)
                .optional();
    }

    @Override
    public Optional<String> bankPayerCode(String countryCode) {
        return jdbc.sql(
                        "SELECT wace_bank_payer_code FROM countries WHERE code = :c AND"
                                + " wace_bank_payer_code IS NOT NULL")
                .param("c", countryCode)
                .query(String.class)
                .optional();
    }

    @Override
    public Optional<String> waceReferenceCode(String category, String partyTypePair, String code) {
        return jdbc.sql(
                        """
                        SELECT wace_code FROM reference_values
                        WHERE category = :category AND code = :code AND enabled AND party_type_pair IN (:pair, 'ALL')
                        ORDER BY (party_type_pair = 'ALL') LIMIT 1
                        """)
                .param("category", category)
                .param("code", code)
                .param("pair", partyTypePair)
                .query(String.class)
                .optional();
    }

    @Override
    public Optional<MobileOperator> findEnabledOperator(long id) {
        return jdbc.sql(
                        "SELECT id, country_code, name, short_code, supports_payout, supports_payin"
                                + " FROM mobile_operators WHERE id = :id AND enabled")
                .param("id", id)
                .query(OPERATOR)
                .optional();
    }
}
