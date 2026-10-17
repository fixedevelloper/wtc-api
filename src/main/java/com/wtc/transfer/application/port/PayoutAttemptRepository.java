package com.wtc.transfer.application.port;

import java.util.List;

public interface PayoutAttemptRepository {

    record Attempt(long id, int attemptNo, String outcome) {}

    List<Attempt> list(long transferId);

    record AttemptView(
            int attemptNo,
            String outcome,
            String providerStatus,
            java.time.Instant startedAt,
            java.time.Instant finishedAt) {}

    List<AttemptView> views(long transferId);

    /**
     * Enregistre <b>avant l'appel réseau</b> une tentative pessimiste (issue {@code AMBIGUOUS}) :
     * si le processus meurt pendant l'appel, la trace reste et interdit de recréer l'ordre.
     */
    long start(long transferId, int attemptNo);

    void finish(
            long attemptId,
            String outcome,
            String requestJson,
            String responseJson,
            String providerStatus,
            String providerReference);
}
