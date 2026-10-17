package com.wtc.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Anomalies, corrections et décisions de l'exécution : rien n'est corrigé ni écarté sans y laisser
 * une trace.
 */
final class EtlReport {

    enum Severity {
        INFO,
        WARN,
        ERROR
    }

    record Entry(
            String stage,
            Severity severity,
            String legacyTable,
            Long legacyId,
            String message,
            Map<String, Object> original) {}

    private final UUID runId = UUID.randomUUID();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Long> counters = new TreeMap<>();
    private String stage = "init";

    UUID runId() {
        return runId;
    }

    void stage(String stage) {
        this.stage = stage;
    }

    void info(String table, Long id, String message) {
        add(Severity.INFO, table, id, message, null);
    }

    void warn(String table, Long id, String message, Map<String, Object> original) {
        add(Severity.WARN, table, id, message, original);
    }

    void error(String table, Long id, String message, Map<String, Object> original) {
        add(Severity.ERROR, table, id, message, original);
    }

    private void add(
            Severity severity,
            String table,
            Long id,
            String message,
            Map<String, Object> original) {
        entries.add(
                new Entry(
                        stage,
                        severity,
                        table,
                        id,
                        message,
                        original == null ? null : new LinkedHashMap<>(original)));
    }

    /**
     * Compte un fait (« users.migrated », « transfers.rejected »…), repris dans le rapport de
     * contrôle.
     */
    void count(String key) {
        counters.merge(key, 1L, Long::sum);
    }

    void count(String key, long n) {
        counters.merge(key, n, Long::sum);
    }

    long counter(String key) {
        return counters.getOrDefault(key, 0L);
    }

    List<Entry> entries() {
        return List.copyOf(entries);
    }

    Map<String, Long> counters() {
        return Map.copyOf(counters);
    }

    long count(Severity severity) {
        return entries.stream().filter(e -> e.severity() == severity).count();
    }
}
