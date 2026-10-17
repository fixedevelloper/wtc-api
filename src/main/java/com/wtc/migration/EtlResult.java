package com.wtc.migration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Résultat d'une exécution : contrôles de réconciliation, compteurs et anomalies. */
public record EtlResult(
        UUID runId,
        boolean dryRun,
        boolean controlsPassed,
        Map<String, Long> counters,
        long errors,
        long warnings,
        List<String> failedControls) {

    public EtlResult {
        counters = Map.copyOf(counters);
        failedControls = List.copyOf(failedControls);
    }
}
