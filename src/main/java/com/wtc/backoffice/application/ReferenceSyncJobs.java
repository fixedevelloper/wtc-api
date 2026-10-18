package com.wtc.backoffice.application;

import com.wtc.backoffice.application.ReferenceSyncService.CountryOutcome;
import com.wtc.backoffice.application.ReferenceSyncService.Progress;
import com.wtc.backoffice.application.ReferenceSyncService.Report;
import com.wtc.backoffice.application.port.ReferenceOverviewRepository;
import com.wtc.backoffice.application.port.ReferenceOverviewRepository.CountryCoverage;
import com.wtc.backoffice.application.port.ReferenceOverviewRepository.LastSync;
import com.wtc.shared.config.WtcProperties;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Import des référentiels Wace exécuté en arrière-plan, avec suivi d'avancement consultable : un
 * seul import à la fois, les dix derniers sont conservés <b>en mémoire</b> (ils ne survivent pas à
 * un redémarrage ; la trace durable est le journal d'audit). Le droit {@code REFERENCE_WRITE} est
 * vérifié au lancement et à chaque lecture.
 */
@Service
public class ReferenceSyncJobs {

    private static final Logger LOG = LoggerFactory.getLogger(ReferenceSyncJobs.class);
    private static final int KEPT = 10;

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    /** Étape d'un pays : PENDING, RUNNING, puis OK, PARTIAL ou FAILED. */
    public record CountryStep(
            String code,
            String status,
            int banks,
            int operators,
            int payerCodes,
            List<String> warnings) {
        public CountryStep {
            warnings = List.copyOf(warnings);
        }
    }

    public record Job(
            String id,
            Status status,
            String country,
            Instant startedAt,
            Instant finishedAt,
            int totalSteps,
            int doneSteps,
            String current,
            List<CountryStep> countries,
            String labels,
            int referenceValues,
            int banks,
            int operators,
            int payerCodes,
            List<String> warnings,
            String error) {
        public Job {
            countries = List.copyOf(countries);
            warnings = List.copyOf(warnings);
        }
    }

    public record Provider(String name, String host, boolean configured) {}

    public record Overview(
            Provider provider,
            List<CountryCoverage> countries,
            int referenceValues,
            LastSync lastSync) {
        public Overview {
            countries = List.copyOf(countries);
        }
    }

    private final ReferenceSyncService sync;
    private final ReferenceOverviewRepository overview;
    private final WtcProperties properties;
    private final Clock clock;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "reference-sync");
                        t.setDaemon(true);
                        return t;
                    });
    private final Map<String, Tracker> jobs = new LinkedHashMap<>();

    ReferenceSyncJobs(
            ReferenceSyncService sync,
            ReferenceOverviewRepository overview,
            WtcProperties properties,
            Clock clock) {
        this.sync = sync;
        this.overview = overview;
        this.properties = properties;
        this.clock = clock;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    public Job start(UUID actor, String country) {
        String only = country == null || country.isBlank() ? null : country.strip();
        List<String> codes = sync.countriesToSync(only).stream().map(c -> c.code()).toList();
        Tracker tracker;
        synchronized (jobs) {
            if (jobs.values().stream().anyMatch(Tracker::running)) {
                throw new DomainException(
                        ErrorCode.SYNC_ALREADY_RUNNING, "Un import est déjà en cours.");
            }
            tracker = new Tracker(UUID.randomUUID().toString(), only, clock.instant(), codes);
            jobs.put(tracker.id, tracker);
            while (jobs.size() > KEPT) {
                jobs.remove(jobs.keySet().iterator().next());
            }
        }
        executor.execute(() -> execute(tracker, actor, only));
        return tracker.snapshot();
    }

    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    public Job get(String id) {
        synchronized (jobs) {
            Tracker t = jobs.get(id);
            if (t == null) {
                throw new DomainException(ErrorCode.SYNC_JOB_NOT_FOUND, "Import introuvable.");
            }
            return t.snapshot();
        }
    }

    /** Les imports récents, le plus récent d'abord. */
    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    public List<Job> recent() {
        synchronized (jobs) {
            return jobs.values().stream()
                    .map(Tracker::snapshot)
                    .sorted(Comparator.comparing(Job::startedAt).reversed())
                    .toList();
        }
    }

    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    public Overview overview() {
        var wace = properties.payment().wace();
        String name = String.valueOf(properties.payment().provider()).toLowerCase();
        boolean real = "wace".equals(name);
        return new Overview(
                new Provider(
                        name,
                        real ? host(wace.baseUrl()) : null,
                        real
                                && wace.baseUrl() != null
                                && !wace.baseUrl().isBlank()
                                && wace.email() != null
                                && !wace.email().isBlank()
                                && wace.password() != null
                                && !wace.password().isBlank()),
                overview.coverage(),
                overview.referenceValues(),
                overview.lastSync().orElse(null));
    }

    private static String host(String url) {
        try {
            return url == null ? null : URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void execute(Tracker tracker, UUID actor, String only) {
        try {
            Report report = sync.run(actor, only, tracker);
            tracker.succeed(report, clock.instant());
        } catch (RuntimeException e) {
            LOG.warn("Import des référentiels en échec : {}", e.toString());
            tracker.fail(
                    e instanceof DomainException d ? d.getMessage() : "Erreur inattendue.",
                    clock.instant());
        }
    }

    /** État mutable d'un import, mis à jour par le fil d'exécution, lu par les requêtes. */
    private static final class Tracker implements Progress {
        final String id;
        final String country;
        final Instant startedAt;
        private Status status = Status.RUNNING;
        private Instant finishedAt;
        private int totalSteps;
        private int doneSteps;
        private String current = "Préparation";
        private final Map<String, CountryStep> steps = new LinkedHashMap<>();
        private String labels = "PENDING";
        private int referenceValues;
        private final List<String> warnings = new ArrayList<>();
        private String error;
        private int banks;
        private int operators;
        private int payerCodes;

        Tracker(String id, String country, Instant startedAt, List<String> codes) {
            this.id = id;
            this.country = country;
            this.startedAt = startedAt;
            this.totalSteps = codes.size() + 1;
            codes.forEach(c -> steps.put(c, new CountryStep(c, "PENDING", 0, 0, 0, List.of())));
        }

        synchronized boolean running() {
            return status == Status.RUNNING;
        }

        @Override
        public synchronized void begin(List<String> countryCodes) {
            totalSteps = countryCodes.size() + 1;
        }

        @Override
        public synchronized void countryStarted(String code) {
            current = "Pays " + code;
            steps.put(code, new CountryStep(code, "RUNNING", 0, 0, 0, List.of()));
        }

        @Override
        public synchronized void countryFinished(CountryOutcome o) {
            steps.put(
                    o.code(),
                    new CountryStep(
                            o.code(),
                            o.status(),
                            o.banks(),
                            o.operators(),
                            o.payerCodes(),
                            o.warnings()));
            doneSteps++;
            banks += o.banks();
            operators += o.operators();
            payerCodes += o.payerCodes();
            warnings.addAll(o.warnings());
        }

        @Override
        public synchronized void labelsStarted() {
            current = "Libellés (motifs, origines des fonds, relations)";
            labels = "RUNNING";
        }

        @Override
        public synchronized void labelsFinished(int values, List<String> labelWarnings) {
            labels = labelWarnings.isEmpty() ? "OK" : "PARTIAL";
            referenceValues = values;
            warnings.addAll(labelWarnings);
            doneSteps++;
        }

        synchronized void succeed(Report report, Instant at) {
            status = Status.SUCCEEDED;
            finishedAt = at;
            current = "Terminé";
            doneSteps = totalSteps;
        }

        synchronized void fail(String message, Instant at) {
            status = Status.FAILED;
            finishedAt = at;
            current = "Échec";
            error = message;
        }

        synchronized Job snapshot() {
            return new Job(
                    id,
                    status,
                    country,
                    startedAt,
                    finishedAt,
                    totalSteps,
                    doneSteps,
                    current,
                    new ArrayList<>(steps.values()),
                    labels,
                    referenceValues,
                    banks,
                    operators,
                    payerCodes,
                    new ArrayList<>(warnings),
                    error);
        }
    }
}
