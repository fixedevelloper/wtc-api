package com.wtc.migration;

import java.nio.file.Path;
import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Réglages de l'ETL ({@code wtc.etl.*}).
 *
 * @param sourceUrl URL JDBC de l'ancienne base MySQL (lecture seule suffit)
 * @param dryRun vrai (défaut) : tout est exécuté puis annulé, seul le rapport est conservé en
 *     fichier ; faux : validé
 * @param publicDir dossier {@code public/} de l'ancien projet : les fichiers y sont référencés par
 *     un chemin relatif (« uploads/xxx.jpg »)
 * @param effectiveFrom date d'effet des tranches de frais migrées (jour de la bascule)
 * @param force autorise l'exécution sur une base déjà exploitée (transferts non migrés présents) :
 *     à éviter
 * @param reportFile fichier JSON du rapport
 */
@ConfigurationProperties("wtc.etl")
public record EtlProperties(
        String sourceUrl,
        String sourceUser,
        String sourcePassword,
        @DefaultValue("true") boolean dryRun,
        Path publicDir,
        LocalDate effectiveFrom,
        @DefaultValue("false") boolean force,
        @DefaultValue("etl-report.json") Path reportFile) {

    @Override
    public String toString() {
        return "EtlProperties[sourceUrl=" + sourceUrl + ", dryRun=" + dryRun + ", secrets=***]";
    }
}
