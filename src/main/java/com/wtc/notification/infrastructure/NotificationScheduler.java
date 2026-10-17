package com.wtc.notification.infrastructure;

import com.wtc.notification.application.EventBridge;
import com.wtc.notification.application.NotificationDispatcher;
import com.wtc.notification.application.OperationsWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Cadence des travaux de notification. Plusieurs instances peuvent tourner : les files se réservent
 * par {@code SKIP LOCKED}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "wtc.notification.worker",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class NotificationScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationScheduler.class);

    private final EventBridge bridge;
    private final NotificationDispatcher dispatcher;
    private final OperationsWatcher watcher;
    private final boolean watcherEnabled;

    NotificationScheduler(
            EventBridge bridge,
            NotificationDispatcher dispatcher,
            OperationsWatcher watcher,
            com.wtc.shared.config.WtcProperties properties) {
        this.bridge = bridge;
        this.dispatcher = dispatcher;
        this.watcher = watcher;
        this.watcherEnabled = properties.notification().worker().opsWatcherEnabled();
    }

    @Scheduled(
            fixedDelayString = "${wtc.notification.worker.poll-interval:10s}",
            initialDelayString = "10s")
    void deliver() {
        try {
            bridge.bridgeDue();
            dispatcher.dispatchDue();
        } catch (RuntimeException e) {
            LOG.error("Cycle de notification en erreur", e);
        }
    }

    @Scheduled(
            fixedDelayString = "${wtc.notification.worker.ops-watch-interval:60s}",
            initialDelayString = "30s")
    void watch() {
        if (!watcherEnabled) {
            return;
        }
        try {
            watcher.check();
            watcher.dailyReport();
        } catch (RuntimeException e) {
            LOG.error("Surveillance en erreur", e);
        }
    }
}
