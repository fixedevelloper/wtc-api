package com.wtc.customer.infrastructure;

import com.wtc.customer.application.port.DestinationRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDestinationRepository implements DestinationRepository {

    private static final String SELECT =
            """
            SELECT id, public_id, party_id, kind, country_code, bank_id, mobile_operator_id, account_holder_name,
                   account_number_enc, account_number_masked, swift_code, ifsc_code, routing_number, is_default
            FROM payout_destinations
            """;

    private static final RowMapper<DestinationRecord> MAPPER =
            (rs, n) -> {
                long bank = rs.getLong("bank_id");
                Long bankId = rs.wasNull() ? null : bank;
                long operator = rs.getLong("mobile_operator_id");
                Long operatorId = rs.wasNull() ? null : operator;
                return new DestinationRecord(
                        rs.getLong("id"),
                        rs.getObject("public_id", UUID.class),
                        rs.getLong("party_id"),
                        rs.getString("kind"),
                        rs.getString("country_code"),
                        bankId,
                        operatorId,
                        rs.getString("account_holder_name"),
                        rs.getBytes("account_number_enc"),
                        rs.getString("account_number_masked"),
                        rs.getString("swift_code"),
                        rs.getString("ifsc_code"),
                        rs.getString("routing_number"),
                        rs.getBoolean("is_default"));
            };

    private final JdbcClient jdbc;

    JdbcDestinationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(DestinationRecord r) {
        jdbc.sql(
                        """
                        INSERT INTO payout_destinations (public_id, party_id, kind, country_code, bank_id, mobile_operator_id,
                            account_holder_name, account_number_enc, account_number_masked, swift_code, ifsc_code, routing_number, is_default)
                        VALUES (:publicId, :partyId, :kind, :country, :bank, :operator, :holder, :enc, :masked, :swift, :ifsc, :routing, :isDefault)
                        """)
                .param("publicId", r.publicId())
                .param("partyId", r.partyId())
                .param("kind", r.kind())
                .param("country", r.countryCode())
                .param("bank", r.bankId())
                .param("operator", r.mobileOperatorId())
                .param("holder", r.accountHolderName())
                .param("enc", r.accountNumberEncrypted())
                .param("masked", r.accountNumberMasked())
                .param("swift", r.swiftCode())
                .param("ifsc", r.ifscCode())
                .param("routing", r.routingNumber())
                .param("isDefault", r.isDefault())
                .update();
    }

    @Override
    public Optional<DestinationRecord> find(long partyId, UUID publicId) {
        return jdbc.sql(
                        SELECT
                                + " WHERE party_id = :party AND public_id = :id AND archived_at IS"
                                + " NULL")
                .param("party", partyId)
                .param("id", publicId)
                .query(MAPPER)
                .optional();
    }

    @Override
    public Optional<DestinationRecord> findOfCustomer(long customerId, UUID publicId) {
        return jdbc.sql(
                        """
                        SELECT d.id, d.public_id, d.party_id, d.kind, d.country_code, d.bank_id, d.mobile_operator_id, d.account_holder_name,
                               d.account_number_enc, d.account_number_masked, d.swift_code, d.ifsc_code, d.routing_number, d.is_default
                        FROM payout_destinations d JOIN parties p ON p.id = d.party_id
                        WHERE d.public_id = :id AND d.archived_at IS NULL AND p.archived_at IS NULL
                          AND p.customer_id = :customer AND p.role = 'BENEFICIARY'
                        """)
                .param("id", publicId)
                .param("customer", customerId)
                .query(MAPPER)
                .optional();
    }

    @Override
    public List<DestinationRecord> list(long partyId) {
        return jdbc.sql(
                        SELECT
                                + " WHERE party_id = :party AND archived_at IS NULL ORDER BY"
                                + " is_default DESC, id")
                .param("party", partyId)
                .query(MAPPER)
                .list();
    }

    @Override
    public void update(DestinationRecord r) {
        jdbc.sql(
                        """
                        UPDATE payout_destinations SET kind = :kind, country_code = :country, bank_id = :bank, mobile_operator_id = :operator,
                            account_holder_name = :holder, account_number_enc = :enc, account_number_masked = :masked,
                            swift_code = :swift, ifsc_code = :ifsc, routing_number = :routing, is_default = :isDefault
                        WHERE id = :id
                        """)
                .param("kind", r.kind())
                .param("country", r.countryCode())
                .param("bank", r.bankId())
                .param("operator", r.mobileOperatorId())
                .param("holder", r.accountHolderName())
                .param("enc", r.accountNumberEncrypted())
                .param("masked", r.accountNumberMasked())
                .param("swift", r.swiftCode())
                .param("ifsc", r.ifscCode())
                .param("routing", r.routingNumber())
                .param("isDefault", r.isDefault())
                .param("id", r.id())
                .update();
    }

    @Override
    public void archive(long id, Instant now) {
        jdbc.sql(
                        "UPDATE payout_destinations SET archived_at = :now, is_default = false"
                                + " WHERE id = :id")
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    @Override
    public void clearDefault(long partyId) {
        jdbc.sql(
                        "UPDATE payout_destinations SET is_default = false WHERE party_id = :p AND"
                                + " is_default AND archived_at IS NULL")
                .param("p", partyId)
                .update();
    }

    @Override
    public void promoteMostRecent(long partyId) {
        jdbc.sql(
                        """
                        UPDATE payout_destinations SET is_default = true WHERE id = (
                            SELECT id FROM payout_destinations WHERE party_id = :p AND archived_at IS NULL ORDER BY id DESC LIMIT 1)
                          AND NOT EXISTS (SELECT 1 FROM payout_destinations WHERE party_id = :p AND archived_at IS NULL AND is_default)
                        """)
                .param("p", partyId)
                .update();
    }
}
