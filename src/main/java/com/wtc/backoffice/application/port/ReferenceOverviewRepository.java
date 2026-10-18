package com.wtc.backoffice.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Lecture de l'état des référentiels issus du fournisseur (couverture par pays, dernier import).
 */
public interface ReferenceOverviewRepository {

    record CountryCoverage(
            String code,
            String name,
            String currency,
            boolean bankPayerCode,
            int banks,
            int operators,
            int operatorsWithoutPayerCode) {}

    record LastSync(Instant at, String actor) {}

    /** Pays actifs qui reçoivent des transferts. */
    List<CountryCoverage> coverage();

    /** Nombre de libellés actifs (motifs, origines des fonds, relations). */
    int referenceValues();

    Optional<LastSync> lastSync();
}
