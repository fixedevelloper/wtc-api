package com.wtc.migration;

import com.wtc.shared.security.SecretCipher;
import com.wtc.shared.storage.ObjectStorage;
import com.wtc.wallet.application.LedgerService;
import com.wtc.wallet.application.WalletService;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Orchestre l'ETL : toutes les étapes dans <b>une seule transaction</b> (tout ou rien), contrôles
 * de réconciliation, rapport. En {@code dry-run} (défaut) la transaction est annulée : on voit le
 * rapport complet sans rien écrire (ni base, ni stockage).
 */
@Component
@Profile("etl")
public class LegacyMigration {

    private static final Logger LOG = LoggerFactory.getLogger(LegacyMigration.class);

    private final EtlProperties props;
    private final JdbcClient target;
    private final PlatformTransactionManager txManager;
    private final DataSource targetDataSource;
    private final SecretCipher cipher;
    private final ObjectStorage storage;
    private final WalletService wallets;
    private final LedgerService ledger;
    private final Clock clock;
    private final JsonMapper json;

    LegacyMigration(
            EtlProperties props,
            JdbcClient target,
            PlatformTransactionManager txManager,
            DataSource targetDataSource,
            SecretCipher cipher,
            ObjectStorage storage,
            WalletService wallets,
            LedgerService ledger,
            Clock clock,
            JsonMapper json) {
        this.props = props;
        this.target = target;
        this.txManager = txManager;
        this.targetDataSource = targetDataSource;
        this.cipher = cipher;
        this.storage = storage;
        this.wallets = wallets;
        this.ledger = ledger;
        this.clock = clock;
        this.json = json;
    }

    public EtlResult run() {
        if (props.sourceUrl() == null || props.sourceUrl().isBlank()) {
            throw new IllegalStateException(
                    "wtc.etl.source-url est obligatoire (URL JDBC de l'ancienne base).");
        }
        if (!props.force()) {
            Long foreign =
                    target.sql("SELECT count(*) FROM transfers WHERE legacy_id IS NULL")
                            .query(Long.class)
                            .single();
            if (foreign > 0) {
                throw new IllegalStateException(
                        "La base cible contient "
                                + foreign
                                + " transfert(s) créés par le nouveau système : l'ETL ne s'exécute"
                                + " qu'avant la mise en service (wtc.etl.force=true pour passer"
                                + " outre).");
            }
        }
        DataSource sourceDs =
                new DriverManagerDataSource(
                        props.sourceUrl(), props.sourceUser(), props.sourcePassword());
        EtlReport report = new EtlReport();
        EtlContext ctx =
                new EtlContext(
                        JdbcClient.create(sourceDs),
                        target,
                        props,
                        report,
                        clock,
                        targetDataSource);
        List<String>[] failed = new List[1];
        new TransactionTemplate(txManager)
                .executeWithoutResult(
                        status -> {
                            new ReferenceStage(ctx).run();
                            new UserStage(ctx).run();
                            new CustomerStage(ctx).run();
                            new KycStage(ctx, storage).run();
                            DestinationFactory destinations = new DestinationFactory(ctx, cipher);
                            new PartyStage(ctx, cipher, destinations).run();
                            new TransferStage(ctx, destinations).run();
                            BalanceStage balances = new BalanceStage(ctx, wallets, ledger);
                            balances.run();
                            new MiscStage(ctx, wallets).run();
                            failed[0] = new Controls(ctx, balances.totalsByCurrency()).run();
                            if (props.dryRun()) {
                                status.setRollbackOnly();
                            } else {
                                persist(report);
                            }
                        });
        EtlResult result =
                new EtlResult(
                        report.runId(),
                        props.dryRun(),
                        failed[0].isEmpty(),
                        report.counters(),
                        report.count(EtlReport.Severity.ERROR),
                        report.count(EtlReport.Severity.WARN),
                        failed[0]);
        writeReport(report, result);
        LOG.info(
                "ETL {} : {} erreurs, {} avertissements, contrôles {}",
                props.dryRun() ? "à blanc" : "validé",
                result.errors(),
                result.warnings(),
                result.controlsPassed() ? "OK" : "EN ÉCART " + result.failedControls());
        return result;
    }

    private void persist(EtlReport report) {
        for (EtlReport.Entry e : report.entries()) {
            target.sql(
                            """
                            INSERT INTO etl_report (run_id, stage, severity, legacy_table, legacy_id, message, original)
                            VALUES (:run, :stage, :sev, :table, :id, :msg, CAST(:orig AS jsonb))
                            """)
                    .param("run", report.runId())
                    .param("stage", e.stage())
                    .param("sev", e.severity().name())
                    .param("table", e.legacyTable())
                    .param("id", e.legacyId())
                    .param("msg", e.message())
                    .param(
                            "orig",
                            e.original() == null ? null : json.writeValueAsString(e.original()))
                    .update();
        }
    }

    private void writeReport(EtlReport report, EtlResult result) {
        try {
            var doc = new java.util.LinkedHashMap<String, Object>();
            doc.put("runId", report.runId().toString());
            doc.put("generatedAt", Timestamp.from(clock.instant()).toString());
            doc.put("dryRun", result.dryRun());
            doc.put("controlsPassed", result.controlsPassed());
            doc.put("failedControls", result.failedControls());
            doc.put("counters", result.counters());
            doc.put("entries", report.entries());
            Files.writeString(
                    props.reportFile(),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
        } catch (java.io.IOException e) {
            LOG.error("Rapport non écrit dans {} : {}", props.reportFile(), e.getMessage());
        }
    }
}
