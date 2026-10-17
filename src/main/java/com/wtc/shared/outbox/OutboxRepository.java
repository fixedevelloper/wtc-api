package com.wtc.shared.outbox;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository {

    record Event(long id, String type, long aggregateId, int attempts) {}

    void enqueue(String aggregateType, long aggregateId, String eventType, Instant notBefore);

    /**
     * Réserve les événements dus (verrouillage {@code SKIP LOCKED} : plusieurs instances sans
     * doublon).
     */
    List<Event> claimDue(List<String> types, int limit, Instant now);

    /** Contenu JSON d'un événement. */
    String payloadOf(long eventId);

    void done(long eventId);

    /** Remet l'événement en file pour plus tard. */
    void retryLater(long eventId, Instant at, String error);

    void fail(long eventId, String error);

    /** Un événement de ce type est-il déjà en file pour ce transfert ? */
    boolean pending(String aggregateType, long aggregateId, String eventType);

    /**
     * Remet en file les événements réservés depuis trop longtemps (processus mort en cours de
     * traitement).
     */
    int releaseStale(Instant olderThan);
}
