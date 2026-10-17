package com.wtc.migration;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * État partagé par les étapes : bases source et cible, rapport, et tables de correspondance ancien
 * id → nouvel id.
 */
final class EtlContext {

    record Ref(long id, UUID publicId) {}

    final JdbcClient source;
    final JdbcClient target;
    final EtlProperties props;
    final EtlReport report;
    final Clock clock;
    final LocalDate today;

    /** pays d'origine → code ISO ; devises et indicatifs de ce code. */
    final Map<Long, String> countryByLegacyId = new HashMap<>();

    final Map<String, String> currencyByCountry = new HashMap<>();
    final Map<String, String> callingCodeByCountry = new HashMap<>();
    final Map<Long, Long> zoneByLegacyId = new HashMap<>();

    /** compte d'origine → utilisateur. */
    final Map<Long, Ref> userByLegacyId = new HashMap<>();

    /**
     * fiche « client » d'origine → titulaire (client) du nouveau modèle ; une fiche d'expéditeur
     * pointe vers son titulaire.
     */
    final Map<Long, Ref> holderByLegacyId = new HashMap<>();

    /** fiche d'origine (titulaire ou expéditeur) → partie SENDER du nouveau modèle. */
    final Map<Long, Ref> senderPartyByLegacyId = new HashMap<>();

    /** bénéficiaire d'origine (contactcustomer) → partie BENEFICIARY. */
    final Map<Long, Ref> beneficiaryPartyByLegacyId = new HashMap<>();

    /** Transactions rejetées (leurs montants expliquent les écarts des contrôles). */
    final java.util.Set<Long> rejectedTransactions = new java.util.HashSet<>();

    long migrationUserId;
    UUID migrationUserPublicId;

    private final javax.sql.DataSource dataSource;

    /** Message de la dernière écriture refusée par {@link #attempt}. */
    String lastError;

    EtlContext(
            JdbcClient source,
            JdbcClient target,
            EtlProperties props,
            EtlReport report,
            Clock clock,
            javax.sql.DataSource dataSource) {
        this.dataSource = dataSource;
        this.source = source;
        this.target = target;
        this.props = props;
        this.report = report;
        this.clock = clock;
        this.today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    /**
     * Exécute dans un point de sauvegarde : si la base refuse l'écriture (contrainte), seule cette
     * écriture est annulée et l'appelant décide quoi rapporter. Sans cela, une erreur SQL
     * condamnerait toute la transaction PostgreSQL.
     *
     * @return faux si l'écriture a été refusée
     */
    boolean attempt(Runnable write) {
        java.sql.Connection connection =
                org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        try {
            java.sql.Savepoint savepoint = connection.setSavepoint();
            try {
                write.run();
                connection.releaseSavepoint(savepoint);
                return true;
            } catch (org.springframework.dao.DataAccessException e) {
                connection.rollback(savepoint);
                lastError = e.getMostSpecificCause().getMessage();
                return false;
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Point de sauvegarde impossible", e);
        } finally {
            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                    connection, dataSource);
        }
    }

    List<Row> rows(String sql) {
        return source.sql(sql).query().listOfRows().stream().map(Row::new).toList();
    }
}
