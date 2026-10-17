package com.wtc.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.support.AbstractIT;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Les migrations s'appliquent sur une base vide et le mapping Hibernate correspond au schéma
 * ({@code ddl-auto=validate}).
 */
class FlywayMigrationIT extends AbstractIT {

    @Autowired Flyway flyway;

    @Autowired JdbcClient jdbc;

    @Test
    void allMigrationsAreApplied() {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).extracting(m -> m.getVersion().getVersion()).contains("1", "2");
        assertThat(applied).allMatch(m -> m.getState() == MigrationState.SUCCESS);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void migrationsAreUnchangedSinceTheyWereApplied() {
        flyway.validate();
    }

    @Test
    void referenceDataIsSeeded() {
        assertThat(jdbc.sql("SELECT count(*) FROM roles").query(Integer.class).single())
                .isEqualTo(4);
        assertThat(
                        jdbc.sql("SELECT count(*) FROM ledger_accounts WHERE kind = 'PLATFORM'")
                                .query(Integer.class)
                                .single())
                .isGreaterThanOrEqualTo(6);
    }

    @Test
    void walletBalancesAlwaysMatchTheLedger() {
        assertThat(
                        jdbc.sql("SELECT count(*) FROM wallet_balance_discrepancies")
                                .query(Integer.class)
                                .single())
                .isZero();
    }
}
