package com.wtc.backoffice.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Lectures agrégées du tableau de bord du back-office (aucune écriture). */
public interface DashboardRepository {

    /** Une ligne par mois civil (UTC), du plus ancien au plus récent. */
    record Month(
            String month,
            BigDecimal transferVolume,
            long transferCount,
            BigDecimal fees,
            BigDecimal topUpVolume,
            long topUpCount,
            long newCustomers) {}

    record Totals(
            long customers,
            long activeCustomers,
            BigDecimal walletBalance,
            long pendingTopUps,
            Map<String, Long> transfersByStatus) {
        public Totals {
            transfersByStatus = Map.copyOf(transfersByStatus);
        }
    }

    /** Les 12 derniers mois, pour une devise ; {@code now} fixe le mois courant. */
    List<Month> lastMonths(String currency, Instant now);

    Totals totals(String currency);
}
