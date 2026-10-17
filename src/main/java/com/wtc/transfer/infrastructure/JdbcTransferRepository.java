package com.wtc.transfer.infrastructure;

import com.wtc.transfer.application.NewTransfer;
import com.wtc.transfer.application.TransferRow;
import com.wtc.transfer.application.port.TransferRepository;
import com.wtc.transfer.domain.TransferStatus;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTransferRepository implements TransferRepository {

    private static final String SELECT =
            """
            SELECT t.id, t.public_id, t.reference, t.customer_id, c.public_id AS customer_public_id,
                   CASE WHEN c.kind = 'BUSINESS' THEN c.business_name ELSE trim(coalesce(c.first_name, '') || ' ' || coalesce(c.last_name, '')) END AS customer_name,
                   t.wallet_id, t.sender_party_id, t.beneficiary_party_id, t.payout_destination_id, t.kind, t.funding_source, t.party_type_pair,
                   t.source_amount, t.fee_amount, t.total_debit, t.source_currency, t.destination_amount, t.destination_currency, t.fx_rate,
                   t.fee_schedule_id, t.purpose_code, t.source_of_funds_code, t.relationship_code, t.comment, t.status, t.failure_reason,
                   t.provider_reference, t.provider_status, t.provider_confirmed_at, t.provider_fee, t.provider_amount_received, t.payout_hold,
                   t.snapshot::text AS snapshot, t.debit_ledger_transaction_id, t.refund_ledger_transaction_id, t.request_fingerprint,
                   t.created_at, t.completed_at
            FROM transfers t JOIN customers c ON c.id = t.customer_id
            """;

    private static final RowMapper<TransferRow> MAPPER =
            (rs, n) ->
                    new TransferRow(
                            rs.getLong("id"),
                            rs.getObject("public_id", UUID.class),
                            rs.getString("reference"),
                            rs.getLong("customer_id"),
                            rs.getObject("customer_public_id", UUID.class),
                            rs.getString("customer_name"),
                            (Long) rs.getObject("wallet_id"),
                            rs.getLong("sender_party_id"),
                            rs.getLong("beneficiary_party_id"),
                            rs.getLong("payout_destination_id"),
                            rs.getString("kind"),
                            rs.getString("funding_source"),
                            rs.getString("party_type_pair"),
                            rs.getBigDecimal("source_amount"),
                            rs.getBigDecimal("fee_amount"),
                            rs.getBigDecimal("total_debit"),
                            rs.getString("source_currency").strip(),
                            rs.getBigDecimal("destination_amount"),
                            rs.getString("destination_currency").strip(),
                            rs.getBigDecimal("fx_rate"),
                            (Long) rs.getObject("fee_schedule_id"),
                            rs.getString("purpose_code"),
                            rs.getString("source_of_funds_code"),
                            rs.getString("relationship_code"),
                            rs.getString("comment"),
                            TransferStatus.valueOf(rs.getString("status")),
                            rs.getString("failure_reason"),
                            rs.getString("provider_reference"),
                            rs.getString("provider_status"),
                            instant(rs.getTimestamp("provider_confirmed_at")),
                            rs.getBigDecimal("provider_fee"),
                            rs.getBigDecimal("provider_amount_received"),
                            rs.getString("payout_hold"),
                            rs.getString("snapshot"),
                            (Long) rs.getObject("debit_ledger_transaction_id"),
                            (Long) rs.getObject("refund_ledger_transaction_id"),
                            rs.getString("request_fingerprint"),
                            rs.getTimestamp("created_at").toInstant(),
                            instant(rs.getTimestamp("completed_at")));

    private final JdbcClient jdbc;

    JdbcTransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    @Override
    public long nextReferenceNumber() {
        return jdbc.sql("SELECT nextval('transfer_reference_seq')").query(Long.class).single();
    }

    @Override
    public long insert(NewTransfer t) {
        return jdbc.sql(
                        """
                        INSERT INTO transfers (public_id, reference, customer_id, wallet_id, sender_party_id, beneficiary_party_id, payout_destination_id,
                            kind, funding_source, party_type_pair, source_amount, fee_amount, source_currency, destination_amount, destination_currency,
                            fx_rate, fee_schedule_id, purpose_code, source_of_funds_code, relationship_code, comment, idempotency_key, request_fingerprint,
                            snapshot, debit_ledger_transaction_id, created_by)
                        VALUES (:publicId, :reference, :customer, :wallet, :sender, :beneficiary, :destination, :kind, :funding, :pair, :amount, :fee,
                            :srcCur, :destAmount, :destCur, :fx, :schedule, :purpose, :sof, :rel, :comment, :idem, :fp, CAST(:snapshot AS jsonb), :debit,
                            (SELECT id FROM users WHERE public_id = :by))
                        RETURNING id
                        """)
                .param("publicId", t.publicId())
                .param("reference", t.reference())
                .param("customer", t.customerId())
                .param("wallet", t.walletId())
                .param("sender", t.senderPartyId())
                .param("beneficiary", t.beneficiaryPartyId())
                .param("destination", t.destinationId())
                .param("kind", t.kind())
                .param("funding", t.fundingSource())
                .param("pair", t.partyTypePair())
                .param("amount", t.sourceAmount())
                .param("fee", t.feeAmount())
                .param("srcCur", t.sourceCurrency())
                .param("destAmount", t.destinationAmount())
                .param("destCur", t.destinationCurrency())
                .param("fx", t.fxRate())
                .param("schedule", t.feeScheduleId())
                .param("purpose", t.purposeCode())
                .param("sof", t.sourceOfFundsCode())
                .param("rel", t.relationshipCode())
                .param("comment", t.comment())
                .param("idem", t.idempotencyKey())
                .param("fp", t.requestFingerprint())
                .param("snapshot", t.snapshotJson())
                .param("debit", t.debitLedgerTransactionId())
                .param("by", t.createdBy())
                .query(Long.class)
                .single();
    }

    @Override
    public Optional<TransferRow> findById(long id) {
        return jdbc.sql(SELECT + " WHERE t.id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public Optional<TransferRow> findByPublicId(UUID publicId) {
        return jdbc.sql(SELECT + " WHERE t.public_id = :id")
                .param("id", publicId)
                .query(MAPPER)
                .optional();
    }

    @Override
    public Optional<TransferRow> lock(long id) {
        return jdbc.sql(SELECT + " WHERE t.id = :id FOR UPDATE OF t")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    @Override
    public Optional<TransferRow> findByIdempotency(long customerId, String key) {
        return jdbc.sql(SELECT + " WHERE t.customer_id = :c AND t.idempotency_key = :k")
                .param("c", customerId)
                .param("k", key)
                .query(MAPPER)
                .optional();
    }

    @Override
    public boolean transition(
            long id, TransferStatus from, TransferStatus to, String failureReason) {
        // completed_at suit le statut dans la même instruction : la base exige COMPLETED <=>
        // completed_at renseigné.
        return jdbc.sql(
                                "UPDATE transfers SET status = :to, failure_reason ="
                                    + " COALESCE(:reason, failure_reason), completed_at = CASE WHEN"
                                    + " :to = 'COMPLETED' THEN now() ELSE completed_at END,"
                                    + " payout_hold = CASE WHEN :to = 'COMPLETED' THEN NULL ELSE"
                                    + " payout_hold END WHERE id = :id AND status = :from")
                        .param("to", to.name())
                        .param("from", from.name())
                        .param("reason", failureReason)
                        .param("id", id)
                        .update()
                == 1;
    }

    @Override
    public void appendHistory(
            long transferId,
            TransferStatus from,
            TransferStatus to,
            String reason,
            String actorType,
            UUID actor,
            String metadataJson) {
        jdbc.sql(
                        "INSERT INTO transfer_status_history (transfer_id, from_status, to_status,"
                            + " reason, actor_type, actor_id, metadata) VALUES (:t, :from, :to,"
                            + " :reason, :type, (SELECT id FROM users WHERE public_id = :actor),"
                            + " CAST(:meta AS jsonb))")
                .param("t", transferId)
                .param("from", from == null ? null : from.name())
                .param("to", to.name())
                .param("reason", reason)
                .param("type", actorType)
                .param("actor", actor, java.sql.Types.OTHER)
                .param("meta", metadataJson)
                .update();
    }

    @Override
    public void markProviderOrder(
            long id,
            String providerReference,
            String providerStatus,
            BigDecimal providerFee,
            BigDecimal amountReceived) {
        jdbc.sql(
                        "UPDATE transfers SET provider_reference = :ref, provider_status = :st,"
                                + " provider_fee = :fee, provider_amount_received = :rec WHERE id ="
                                + " :id")
                .param("ref", providerReference)
                .param("st", providerStatus)
                .param("fee", providerFee)
                .param("rec", amountReceived)
                .param("id", id)
                .update();
    }

    @Override
    public void markConfirmed(long id) {
        jdbc.sql(
                        "UPDATE transfers SET provider_confirmed_at = now() WHERE id = :id AND"
                                + " provider_confirmed_at IS NULL")
                .param("id", id)
                .update();
    }

    @Override
    public void markPolled(long id, String providerStatus) {
        jdbc.sql(
                        "UPDATE transfers SET provider_polled_at = now(), provider_status ="
                                + " COALESCE(:st, provider_status) WHERE id = :id")
                .param("st", providerStatus)
                .param("id", id)
                .update();
    }

    @Override
    public void setHold(long id, String hold) {
        jdbc.sql("UPDATE transfers SET payout_hold = :h WHERE id = :id")
                .param("h", hold)
                .param("id", id)
                .update();
    }

    @Override
    public void clearHold(long id) {
        jdbc.sql("UPDATE transfers SET payout_hold = NULL WHERE id = :id").param("id", id).update();
    }

    @Override
    public boolean refundTransition(long id, long ledgerTransactionId) {
        return jdbc.sql(
                                "UPDATE transfers SET status = 'REFUNDED',"
                                        + " refund_ledger_transaction_id = :tx, payout_hold = NULL"
                                        + " WHERE id = :id AND status = 'FAILED'")
                        .param("tx", ledgerTransactionId)
                        .param("id", id)
                        .update()
                == 1;
    }

    @Override
    public Page listOfCustomer(long customerId, int page, int size, boolean newestFirst) {
        String order = newestFirst ? "DESC" : "ASC";
        List<TransferRow> rows =
                jdbc.sql(
                                SELECT
                                        + " WHERE t.customer_id = :c AND t.hidden_at IS NULL ORDER"
                                        + " BY t.created_at "
                                        + order
                                        + ", t.id "
                                        + order
                                        + " LIMIT :size OFFSET :offset")
                        .param("c", customerId)
                        .param("size", size)
                        .param("offset", (long) page * size)
                        .query(MAPPER)
                        .list();
        long total =
                jdbc.sql(
                                "SELECT count(*) FROM transfers WHERE customer_id = :c AND"
                                        + " hidden_at IS NULL")
                        .param("c", customerId)
                        .query(Long.class)
                        .single();
        return new Page(rows, total);
    }

    @Override
    public Page search(Filter f, int page, int size, boolean newestFirst) {
        String order = newestFirst ? "DESC" : "ASC";
        String where =
                """
                 WHERE (CAST(:status AS text) IS NULL OR t.status = :status)
                   AND (CAST(:customer AS uuid) IS NULL OR c.public_id = :customer)
                   AND (CAST(:from AS timestamptz) IS NULL OR t.created_at >= :from)
                   AND (CAST(:to AS timestamptz) IS NULL OR t.created_at < :to)
                   AND (CAST(:search AS text) IS NULL OR t.reference ILIKE :p OR t.provider_reference ILIKE :p OR c.first_name ILIKE :p OR c.last_name ILIKE :p
                        OR c.business_name ILIKE :p OR c.phone_e164 ILIKE :p)
                """;
        String pattern =
                f.search() == null
                        ? null
                        : "%"
                                + f.search()
                                        .replace("\\", "\\\\")
                                        .replace("%", "\\%")
                                        .replace("_", "\\_")
                                + "%";
        var rows =
                bind(
                                jdbc.sql(
                                        SELECT
                                                + where
                                                + " ORDER BY t.created_at "
                                                + order
                                                + ", t.id "
                                                + order
                                                + " LIMIT :size OFFSET :offset"),
                                f,
                                pattern)
                        .param("size", size)
                        .param("offset", (long) page * size)
                        .query(MAPPER)
                        .list();
        long total =
                bind(
                                jdbc.sql(
                                        "SELECT count(*) FROM transfers t JOIN customers c ON c.id"
                                                + " = t.customer_id"
                                                + where),
                                f,
                                pattern)
                        .query(Long.class)
                        .single();
        return new Page(rows, total);
    }

    private static JdbcClient.StatementSpec bind(
            JdbcClient.StatementSpec spec, Filter f, String pattern) {
        return spec.param("status", f.status() == null ? null : f.status().name())
                .param("customer", f.customerId(), java.sql.Types.OTHER)
                .param("from", f.from() == null ? null : Timestamp.from(f.from()))
                .param("to", f.to() == null ? null : Timestamp.from(f.to()))
                .param("search", f.search())
                .param("p", pattern);
    }

    @Override
    public List<TransferRow> dueForPolling(Instant before, int limit) {
        return jdbc.sql(
                        SELECT
                                + " WHERE t.status = 'PROCESSING' AND t.provider_reference IS NOT"
                                + " NULL AND (t.payout_hold IS NULL OR t.payout_hold ="
                                + " 'PROVIDER_LOCKED') AND (t.provider_polled_at IS NULL OR"
                                + " t.provider_polled_at < :before) ORDER BY t.provider_polled_at"
                                + " NULLS FIRST, t.id LIMIT :limit")
                .param("before", Timestamp.from(before))
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    @Override
    public List<HistoryEntry> history(long transferId) {
        return jdbc.sql(
                        "SELECT from_status, to_status, reason, actor_type, occurred_at FROM"
                                + " transfer_status_history WHERE transfer_id = :t ORDER BY id")
                .param("t", transferId)
                .query(
                        (rs, n) ->
                                new HistoryEntry(
                                        rs.getString("from_status"),
                                        rs.getString("to_status"),
                                        rs.getString("reason"),
                                        rs.getString("actor_type"),
                                        rs.getTimestamp("occurred_at").toInstant()))
                .list();
    }
}
