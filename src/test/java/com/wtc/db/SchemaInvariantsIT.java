package com.wtc.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.support.TestcontainersConfig;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Exécute les invariants financiers du schéma sur une base vierge : écritures déséquilibrées
 * refusées, grand livre immuable, solde jamais négatif, double validation, barèmes sans
 * chevauchement, idempotence, anti-rejeu, etc. Le script est le même que {@code
 * tools/check_schema.sh}.
 */
@Testcontainers
class SchemaInvariantsIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(TestcontainersConfig.IMAGE));

    @Test
    void everyFinancialInvariantHolds() throws IOException, InterruptedException {
        POSTGRES.copyFileToContainer(
                MountableFile.forClasspathResource("db/migration/V1__baseline.sql"), "/tmp/v1.sql");
        POSTGRES.copyFileToContainer(
                MountableFile.forClasspathResource("db/schema-invariants.sql"),
                "/tmp/invariants.sql");

        ExecResult schema = psql("-f", "/tmp/v1.sql");
        assertThat(schema.getExitCode())
                .as("chargement du schéma V1 : %s", schema.getStderr())
                .isZero();

        ExecResult invariants = psql("-f", "/tmp/invariants.sql");
        String output = invariants.getStderr() + invariants.getStdout();
        assertThat(invariants.getExitCode()).as("invariants :\n%s", output).isZero();
        assertThat(output).doesNotContain("ÉCHEC");
        long verified = output.lines().filter(l -> l.contains("[OK]")).count();
        assertThat(verified).as("nombre d'invariants vérifiés").isGreaterThanOrEqualTo(55);
    }

    private static ExecResult psql(String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 7];
        command[0] = "psql";
        command[1] = "-U";
        command[2] = POSTGRES.getUsername();
        command[3] = "-d";
        command[4] = POSTGRES.getDatabaseName();
        command[5] = "-v";
        command[6] = "ON_ERROR_STOP=1";
        System.arraycopy(args, 0, command, 7, args.length);
        return POSTGRES.execInContainer(command);
    }
}
