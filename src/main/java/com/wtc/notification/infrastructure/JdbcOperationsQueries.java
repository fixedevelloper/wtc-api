package com.wtc.notification.infrastructure;

import com.wtc.notification.application.port.OperationsQueries;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Lecture seule de tables d'autres modules (transferts, grand livre, ajustements) pour la
 * surveillance : ce sont les requêtes du runbook, exécutées par la machine. Aucune écriture, aucune
 * donnée personnelle remontée.
 */
@Repository
class JdbcOperationsQueries implements OperationsQueries {

    private final JdbcClient jdbc;

    JdbcOperationsQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Finding> heldTransfers() {
        return jdbc.sql(
                        "SELECT id, reference, payout_hold, status FROM transfers WHERE payout_hold"
                                + " IS NOT NULL ORDER BY id")
                .query(
                        (rs, n) ->
                                new Finding(
                                        "ops:hold:"
                                                + rs.getLong("id")
                                                + ":"
                                                + rs.getString("payout_hold"),
                                        "Transfert bloqué : " + rs.getString("payout_hold"),
                                        "Transfert "
                                                + rs.getString("reference")
                                                + " ("
                                                + rs.getString("status")
                                                + ") : décision humaine requise (voir le"
                                                + " runbook)."))
                .list();
    }

    @Override
    public List<Finding> failedOutboxEvents() {
        return jdbc.sql(
                        "SELECT id, event_type, aggregate_id, last_error FROM outbox_events WHERE"
                                + " status = 'FAILED' ORDER BY id")
                .query(
                        (rs, n) ->
                                new Finding(
                                        "ops:event:" + rs.getLong("id"),
                                        "Événement en échec : " + rs.getString("event_type"),
                                        "Événement "
                                                + rs.getLong("id")
                                                + " (objet "
                                                + rs.getLong("aggregate_id")
                                                + ") : "
                                                + rs.getString("last_error")))
                .list();
    }

    @Override
    public List<Finding> walletDiscrepancies(String dayKey) {
        return jdbc.sql(
                        "SELECT wallet_id, materialized_balance, ledger_balance FROM"
                                + " wallet_balance_discrepancies")
                .query(
                        (rs, n) ->
                                new Finding(
                                        "ops:discrepancy:" + rs.getLong("wallet_id") + ":" + dayKey,
                                        "ÉCART DE SOLDE",
                                        "Portefeuille "
                                                + rs.getLong("wallet_id")
                                                + " : solde "
                                                + rs.getBigDecimal("materialized_balance")
                                                        .toPlainString()
                                                + " ≠ grand livre "
                                                + rs.getBigDecimal("ledger_balance").toPlainString()
                                                + ". Ne pas corriger par UPDATE : écriture de"
                                                + " correction."))
                .list();
    }

    @Override
    public List<Finding> stuckTransfers(String dayKey) {
        return jdbc.sql(
                        "SELECT id, reference, status FROM transfers WHERE status IN"
                                + " ('PAYOUT_PENDING', 'PROCESSING') AND payout_hold IS NULL AND"
                                + " created_at < now() - interval '1 hour' ORDER BY id")
                .query(
                        (rs, n) ->
                                new Finding(
                                        "ops:stuck:" + rs.getLong("id") + ":" + dayKey,
                                        "Transfert en cours depuis plus d'une heure",
                                        "Transfert "
                                                + rs.getString("reference")
                                                + " ("
                                                + rs.getString("status")
                                                + ")."))
                .list();
    }

    @Override
    public List<Finding> unrefundedFailures(String dayKey) {
        return jdbc.sql(
                        "SELECT id, reference FROM transfers WHERE status = 'FAILED' AND updated_at"
                                + " < now() - interval '10 minutes' ORDER BY id")
                .query(
                        (rs, n) ->
                                new Finding(
                                        "ops:failed:" + rs.getLong("id") + ":" + dayKey,
                                        "Transfert échoué non remboursé",
                                        "Transfert "
                                                + rs.getString("reference")
                                                + " : le remboursement n'a pas pu être posé"
                                                + " (portefeuille clos ?)."))
                .list();
    }

    @Override
    public List<Finding> staleAdjustments(String dayKey) {
        return jdbc.sql(
                        "SELECT id, public_id FROM balance_adjustments WHERE status = 'PENDING' AND"
                                + " created_at < now() - interval '2 days' ORDER BY id")
                .query(
                        (rs, n) ->
                                new Finding(
                                        "ops:adjustment:" + rs.getLong("id") + ":" + dayKey,
                                        "Ajustement de solde en attente depuis plus de 2 jours",
                                        "Ajustement "
                                                + rs.getObject("public_id")
                                                + " à approuver ou refuser."))
                .list();
    }

    @Override
    public List<String> dailySummary(java.time.LocalDate day) {
        java.sql.Date from = java.sql.Date.valueOf(day);
        java.sql.Date to = java.sql.Date.valueOf(day.plusDays(1));
        List<String> lines = new java.util.ArrayList<>();
        // Les bornes sont en UTC : la journée commence à minuit UTC quel que soit le fuseau du
        // serveur.
        jdbc
                .sql(
                        """
                        SELECT source_currency,
                               count(*) FILTER (WHERE created_at >= (CAST(:from AS date)::timestamp AT TIME ZONE 'UTC')
                                                  AND created_at <  (CAST(:to AS date)::timestamp AT TIME ZONE 'UTC')) AS created,
                               count(*) FILTER (WHERE status = 'COMPLETED'
                                                  AND completed_at >= (CAST(:from AS date)::timestamp AT TIME ZONE 'UTC')
                                                  AND completed_at <  (CAST(:to AS date)::timestamp AT TIME ZONE 'UTC')) AS completed,
                               COALESCE(sum(source_amount) FILTER (WHERE status = 'COMPLETED'
                                                  AND completed_at >= (CAST(:from AS date)::timestamp AT TIME ZONE 'UTC')
                                                  AND completed_at <  (CAST(:to AS date)::timestamp AT TIME ZONE 'UTC')), 0) AS volume,
                               COALESCE(sum(fee_amount) FILTER (WHERE status = 'COMPLETED'
                                                  AND completed_at >= (CAST(:from AS date)::timestamp AT TIME ZONE 'UTC')
                                                  AND completed_at <  (CAST(:to AS date)::timestamp AT TIME ZONE 'UTC')), 0) AS fees,
                               count(*) FILTER (WHERE status = 'REFUNDED'
                                                  AND updated_at >= (CAST(:from AS date)::timestamp AT TIME ZONE 'UTC')
                                                  AND updated_at <  (CAST(:to AS date)::timestamp AT TIME ZONE 'UTC')) AS refunded
                        FROM transfers GROUP BY source_currency ORDER BY source_currency
                        """)
                .param("from", from)
                .param("to", to)
                .query(
                        (rs, n) ->
                                rs.getString("source_currency")
                                        + " : "
                                        + rs.getLong("created")
                                        + " créés, "
                                        + rs.getLong("completed")
                                        + " payés ("
                                        + rs.getBigDecimal("volume")
                                                .stripTrailingZeros()
                                                .toPlainString()
                                        + ", frais "
                                        + rs.getBigDecimal("fees")
                                                .stripTrailingZeros()
                                                .toPlainString()
                                        + "), "
                                        + rs.getLong("refunded")
                                        + " remboursés")
                .list()
                .stream()
                .filter(l -> !l.contains(" 0 créés, 0 payés") || !l.endsWith(" 0 remboursés"))
                .forEach(lines::add);
        long customers =
                jdbc.sql(
                                "SELECT count(*) FROM customers WHERE created_at >= (CAST(:from AS"
                                        + " date)::timestamp AT TIME ZONE 'UTC') AND created_at <"
                                        + " (CAST(:to AS date)::timestamp AT TIME ZONE 'UTC')")
                        .param("from", from)
                        .param("to", to)
                        .query(Long.class)
                        .single();
        long kycPending =
                jdbc.sql("SELECT count(*) FROM customers WHERE kyc_status = 'PENDING'")
                        .query(Long.class)
                        .single();
        long adjustments =
                jdbc.sql("SELECT count(*) FROM balance_adjustments WHERE status = 'PENDING'")
                        .query(Long.class)
                        .single();
        lines.add("Nouveaux clients : " + customers);
        lines.add("Pièces d'identité à examiner : " + kycPending);
        lines.add("Ajustements de solde à valider : " + adjustments);
        return lines;
    }

    @Override
    public List<Finding> failedNotifications() {
        return jdbc.sql(
                        "SELECT id, channel, template, last_error FROM notification_outbox WHERE"
                                + " status = 'FAILED' ORDER BY id")
                .query(
                        (rs, n) ->
                                new Finding(
                                        "ops:notification:" + rs.getLong("id"),
                                        "Notification en échec définitif",
                                        rs.getString("channel")
                                                + " / "
                                                + rs.getString("template")
                                                + " : "
                                                + rs.getString("last_error")))
                .list();
    }
}
