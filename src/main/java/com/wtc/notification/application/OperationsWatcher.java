package com.wtc.notification.application;

import com.wtc.notification.application.port.OperationsQueries;
import com.wtc.notification.application.port.OperationsQueries.Finding;
import com.wtc.shared.config.WtcProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Surveille ce qui demande un humain et le signale à l'équipe par Telegram. Chaque anomalie n'est
 * annoncée qu'<b>une fois</b> (clé d'unicité) ; celles qui durent sont rappelées <b>une fois par
 * jour</b> (la clé contient la date).
 */
@Component
public class OperationsWatcher {

    private final OperationsQueries queries;
    private final NotificationService notifications;
    private final Clock clock;
    private final String adminChatId;
    private volatile LocalDate lastReportDay;

    OperationsWatcher(
            OperationsQueries queries,
            NotificationService notifications,
            Clock clock,
            WtcProperties properties) {
        this.queries = queries;
        this.notifications = notifications;
        this.clock = clock;
        this.adminChatId = properties.notification().telegram().adminChatId();
    }

    /**
     * Synthèse quotidienne de la veille (UTC), envoyée à partir de 07:00 UTC, une seule fois par
     * jour (clé d'unicité) même si plusieurs instances ou plusieurs cycles passent.
     *
     * @return vrai si la synthèse a été mise en file par cet appel
     */
    public boolean dailyReport() {
        if (adminChatId == null || adminChatId.isBlank()) {
            return false;
        }
        java.time.ZonedDateTime now = clock.instant().atZone(ZoneOffset.UTC);
        if (now.getHour() < 7) {
            return false;
        }
        LocalDate yesterday = now.toLocalDate().minusDays(1);
        if (yesterday.equals(lastReportDay)) {
            return false; // déjà traitée par cette instance : évite de relancer les requêtes à
            // chaque cycle
        }
        boolean created =
                notifications.enqueue(
                        Channel.TELEGRAM,
                        adminChatId,
                        "daily-report",
                        Map.of(
                                "day",
                                yesterday.toString(),
                                "lines",
                                String.join("\n", queries.dailySummary(yesterday))),
                        "report:" + yesterday);
        lastReportDay = yesterday;
        return created;
    }

    /**
     * @return le nombre de nouvelles alertes mises en file
     */
    public int check() {
        if (adminChatId == null || adminChatId.isBlank()) {
            return 0; // personne à prévenir : les requêtes du runbook restent disponibles à la main
        }
        String day = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
        List<Finding> findings = new ArrayList<>();
        findings.addAll(queries.heldTransfers());
        findings.addAll(queries.failedOutboxEvents());
        findings.addAll(queries.walletDiscrepancies(day));
        findings.addAll(queries.stuckTransfers(day));
        findings.addAll(queries.unrefundedFailures(day));
        findings.addAll(queries.staleAdjustments(day));
        findings.addAll(queries.failedNotifications());
        int created = 0;
        for (Finding f : findings) {
            if (notifications.enqueue(
                    Channel.TELEGRAM,
                    adminChatId,
                    "ops-alert",
                    Map.of("title", f.title(), "details", f.details()),
                    f.key())) {
                created++;
            }
        }
        return created;
    }
}
