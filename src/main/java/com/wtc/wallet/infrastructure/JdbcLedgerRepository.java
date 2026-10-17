package com.wtc.wallet.infrastructure;

import com.wtc.wallet.application.PostingRequest;
import com.wtc.wallet.application.port.LedgerRepository;
import com.wtc.wallet.domain.Direction;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLedgerRepository implements LedgerRepository {

    private static final RowMapper<Existing> EXISTING =
            (rs, n) ->
                    new Existing(
                            rs.getLong("id"),
                            rs.getObject("public_id", UUID.class),
                            rs.getString("kind"),
                            rs.getBigDecimal("total_debits"));

    private final JdbcClient jdbc;

    JdbcLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Existing> insertTransaction(PostingRequest r) {
        // ON CONFLICT : deux demandes simultanées avec la même référence ne s'affrontent pas, une
        // seule gagne.
        return jdbc.sql(
                        """
                        INSERT INTO ledger_transactions (kind, reference, description, created_by, related_type, related_id)
                        VALUES (:kind, :ref, :desc, (SELECT id FROM users WHERE public_id = :by), :rt, :rid)
                        ON CONFLICT (reference) DO NOTHING
                        RETURNING id, public_id, kind, 0::numeric AS total_debits
                        """)
                .param("kind", r.kind().name())
                .param("ref", r.reference())
                .param("desc", r.description())
                .param("by", r.createdBy(), java.sql.Types.OTHER)
                .param("rt", r.relatedType())
                .param("rid", r.relatedId())
                .query(EXISTING)
                .optional();
    }

    @Override
    public Optional<Existing> findByReference(String reference) {
        return jdbc.sql(
                        """
                        SELECT t.id, t.public_id, t.kind,
                               COALESCE((SELECT sum(amount) FROM ledger_entries e WHERE e.ledger_transaction_id = t.id AND e.direction = 'DEBIT'), 0) AS total_debits
                        FROM ledger_transactions t WHERE t.reference = :ref
                        """)
                .param("ref", reference)
                .query(EXISTING)
                .optional();
    }

    @Override
    public long walletAccountId(long walletId) {
        return jdbc.sql("SELECT id FROM ledger_accounts WHERE wallet_id = :w")
                .param("w", walletId)
                .query(Long.class)
                .single();
    }

    @Override
    public long platformAccountId(String baseCode, String currency) {
        // XAF garde le code historique ; les autres devises ajoutent un suffixe
        // (PLATFORM_FEE_REVENUE.EUR).
        String code = "XAF".equals(currency) ? baseCode : baseCode + "." + currency;
        jdbc.sql(
                        "INSERT INTO ledger_accounts (code, kind, currency_code, name) VALUES"
                                + " (:code, 'PLATFORM', :cur, :name) ON CONFLICT (code) DO NOTHING")
                .param("code", code)
                .param("cur", currency)
                .param("name", baseCode + " (" + currency + ")")
                .update();
        return jdbc.sql("SELECT id FROM ledger_accounts WHERE code = :code AND kind = 'PLATFORM'")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    @Override
    public void insertEntry(
            long transactionId,
            long accountId,
            Direction direction,
            BigDecimal amount,
            String currency) {
        jdbc.sql(
                        "INSERT INTO ledger_entries (ledger_transaction_id, ledger_account_id,"
                            + " direction, amount, currency_code) VALUES (:t, :a, :d, :amount, :c)")
                .param("t", transactionId)
                .param("a", accountId)
                .param("d", direction.name())
                .param("amount", amount)
                .param("c", currency)
                .update();
    }

    @Override
    public boolean applyToWallet(
            long walletId, Direction direction, BigDecimal amount, String currency) {
        String sql =
                direction == Direction.DEBIT
                        ? "UPDATE wallets SET balance = balance - :a, version = version + 1 WHERE"
                              + " id = :id AND currency_code = :c AND status = 'ACTIVE' AND balance"
                              + " >= :a"
                        : "UPDATE wallets SET balance = balance + :a, version = version + 1 WHERE"
                                + " id = :id AND currency_code = :c AND status <> 'CLOSED'";
        return jdbc.sql(sql).param("a", amount).param("id", walletId).param("c", currency).update()
                == 1;
    }

    @Override
    public Optional<WalletStateRow> walletState(long walletId) {
        return jdbc.sql("SELECT status, balance, currency_code FROM wallets WHERE id = :id")
                .param("id", walletId)
                .query(
                        (rs, n) ->
                                new WalletStateRow(
                                        rs.getString("status"),
                                        rs.getBigDecimal("balance"),
                                        rs.getString("currency_code").strip()))
                .optional();
    }
}
