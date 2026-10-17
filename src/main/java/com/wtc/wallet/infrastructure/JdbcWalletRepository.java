package com.wtc.wallet.infrastructure;

import com.wtc.wallet.application.LedgerEntryView;
import com.wtc.wallet.application.Page;
import com.wtc.wallet.application.port.WalletRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Portefeuilles. Le client est retrouvé par son identifiant public dans la requête SQL (jointure
 * sur {@code customers}) : le module portefeuille ne dépend ainsi d'aucune classe du module client.
 */
@Repository
class JdbcWalletRepository implements WalletRepository {

    private static final String SELECT =
            """
            SELECT w.id, w.public_id, w.customer_id, c.public_id AS customer_public_id, w.currency_code, w.balance, w.status
            FROM wallets w JOIN customers c ON c.id = w.customer_id
            """;

    private static final RowMapper<WalletRow> MAPPER =
            (rs, n) ->
                    new WalletRow(
                            rs.getLong("id"),
                            rs.getObject("public_id", UUID.class),
                            rs.getLong("customer_id"),
                            rs.getObject("customer_public_id", UUID.class),
                            rs.getString("currency_code").strip(),
                            rs.getBigDecimal("balance"),
                            rs.getString("status"));

    private final JdbcClient jdbc;

    JdbcWalletRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WalletRow> ensure(UUID customerPublicId, String currency) {
        jdbc.sql(
                        "INSERT INTO wallets (customer_id, currency_code) SELECT id, :cur FROM"
                                + " customers WHERE public_id = :c ON CONFLICT (customer_id,"
                                + " currency_code) DO NOTHING")
                .param("cur", currency)
                .param("c", customerPublicId)
                .update();
        Optional<WalletRow> wallet = find(customerPublicId, currency);
        wallet.ifPresent(
                w ->
                        jdbc.sql(
                                        """
                                        INSERT INTO ledger_accounts (code, kind, wallet_id, currency_code, name)
                                        VALUES ('WALLET:' || CAST(:pid AS text), 'CUSTOMER_WALLET', :id, :cur, :name) ON CONFLICT DO NOTHING
                                        """)
                                .param("pid", w.publicId())
                                .param("id", w.id())
                                .param("cur", currency)
                                .param("name", "Portefeuille " + currency)
                                .update());
        return wallet;
    }

    @Override
    public Optional<WalletRow> find(UUID customerPublicId, String currency) {
        return jdbc.sql(SELECT + " WHERE c.public_id = :c AND w.currency_code = :cur")
                .param("c", customerPublicId)
                .param("cur", currency)
                .query(MAPPER)
                .optional();
    }

    @Override
    public List<WalletRow> listOfCustomer(UUID customerPublicId) {
        return jdbc.sql(SELECT + " WHERE c.public_id = :c ORDER BY w.currency_code")
                .param("c", customerPublicId)
                .query(MAPPER)
                .list();
    }

    @Override
    public Page<LedgerEntryView> entries(long walletId, int page, int size, boolean newestFirst) {
        String order = newestFirst ? "DESC" : "ASC";
        List<LedgerEntryView> rows =
                jdbc.sql(
                                """
                                SELECT e.id, t.occurred_at, t.kind, e.direction, e.amount, e.currency_code, t.description, t.reference
                                FROM ledger_entries e
                                  JOIN ledger_accounts a ON a.id = e.ledger_account_id
                                  JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
                                WHERE a.wallet_id = :w
                                ORDER BY t.occurred_at\s\
                                """
                                        + order
                                        + ", e.id "
                                        + order
                                        + " LIMIT :size OFFSET :offset")
                        .param("w", walletId)
                        .param("size", size)
                        .param("offset", (long) page * size)
                        .query(
                                (rs, n) ->
                                        new LedgerEntryView(
                                                rs.getLong("id"),
                                                rs.getTimestamp("occurred_at").toInstant(),
                                                rs.getString("kind"),
                                                rs.getString("direction"),
                                                rs.getBigDecimal("amount"),
                                                rs.getString("currency_code").strip(),
                                                rs.getString("description"),
                                                rs.getString("reference")))
                        .list();
        long total =
                jdbc.sql(
                                "SELECT count(*) FROM ledger_entries e JOIN ledger_accounts a ON"
                                        + " a.id = e.ledger_account_id WHERE a.wallet_id = :w")
                        .param("w", walletId)
                        .query(Long.class)
                        .single();
        return new Page<>(rows, total);
    }
}
