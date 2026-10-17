package com.wtc.notification.application.port;

import java.util.List;

/**
 * Lectures de surveillance : ce qui demande l'attention d'un humain (requêtes du runbook, exécutées
 * automatiquement).
 */
public interface OperationsQueries {

    /** Une anomalie : clé stable (pour ne l'annoncer qu'une fois), titre et détail lisible. */
    record Finding(String key, String title, String details) {}

    /** Transferts bloqués pour décision humaine. */
    List<Finding> heldTransfers();

    /** Événements d'envoi définitivement en échec. */
    List<Finding> failedOutboxEvents();

    /** Soldes qui ne correspondent plus au grand livre (à ne jamais voir). */
    List<Finding> walletDiscrepancies(String dayKey);

    /** Transferts ouverts depuis plus d'une heure sans blocage (file saturée, fournisseur lent). */
    List<Finding> stuckTransfers(String dayKey);

    /** Transferts échoués et non remboursés. */
    List<Finding> unrefundedFailures(String dayKey);

    /** Ajustements de solde en attente depuis plus de deux jours. */
    List<Finding> staleAdjustments(String dayKey);

    /**
     * Lignes de synthèse de la journée UTC {@code day} (transferts par devise, nouveaux clients,
     * files de travail).
     */
    List<String> dailySummary(java.time.LocalDate day);

    /** Notifications définitivement en échec. */
    List<Finding> failedNotifications();
}
