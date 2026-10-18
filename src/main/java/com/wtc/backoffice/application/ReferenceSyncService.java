package com.wtc.backoffice.application;

import com.wtc.payment.application.PayoutGateway;
import com.wtc.payment.application.PayoutGateway.PayoutService;
import com.wtc.payment.application.PayoutGateway.Result;
import com.wtc.reference.application.ReferenceImportService;
import com.wtc.reference.application.port.PayoutCatalogRepository.OperatorRef;
import com.wtc.reference.application.port.PayoutCatalogRepository.SyncCountry;
import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Importe depuis Wace ce qu'il faut pour envoyer : le <b>code payeur</b> de chaque pays (banque) et
 * de chaque opérateur mobile (spécification, section VII), la liste des banques et les libellés
 * (relations, origines des fonds, motifs).
 *
 * <p>Les appels réseau se font hors transaction ; chaque écriture est indépendante. Un import
 * n'active, ne désactive ni ne supprime jamais rien : il complète et met à jour. Les opérateurs
 * sont rapprochés des services de Wace par code court ou par nom ; ceux qui ne correspondent à rien
 * sont signalés (à renseigner à la main), jamais devinés.
 */
@Service
public class ReferenceSyncService {

    private static final String BANK_SERVICE_CODE = "B";
    private static final List<String> PAIRS = List.of("P2P", "P2B", "B2P", "B2B");

    /** Résultat : compteurs d'éléments importés et anomalies à traiter. */
    public record Report(
            Instant startedAt,
            int banks,
            int operators,
            int payerCodes,
            int referenceValues,
            List<String> warnings) {
        public Report {
            warnings = List.copyOf(warnings);
        }
    }

    /** Résultat de l'import d'un pays. {@code status} : OK, PARTIAL (avertissements) ou FAILED. */
    public record CountryOutcome(
            String code,
            String status,
            int banks,
            int operators,
            int payerCodes,
            List<String> warnings) {
        public CountryOutcome {
            warnings = List.copyOf(warnings);
        }
    }

    /** Suivi de l'avancement (appelé depuis le fil qui exécute l'import). */
    public interface Progress {
        Progress NONE = new Progress() {};

        /** Pays à traiter, dans l'ordre ; les libellés forment une étape de plus. */
        default void begin(List<String> countryCodes) {}

        default void countryStarted(String code) {}

        default void countryFinished(CountryOutcome outcome) {}

        default void labelsStarted() {}

        default void labelsFinished(int values, List<String> warnings) {}
    }

    private final PayoutGateway gateway;
    private final ReferenceImportService references;
    private final AuditTrail audit;
    private final Clock clock;

    ReferenceSyncService(
            PayoutGateway gateway,
            ReferenceImportService references,
            AuditTrail audit,
            Clock clock) {
        this.gateway = gateway;
        this.references = references;
        this.audit = audit;
        this.clock = clock;
    }

    /** Importe tous les pays récepteurs, ou seulement {@code onlyCountry} s'il est renseigné. */
    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    public Report sync(UUID actor, String onlyCountry) {
        return run(actor, onlyCountry, Progress.NONE);
    }

    /**
     * Pays qui seront traités ; refuse un pays inconnu, désactivé ou qui ne reçoit pas. Sans
     * contrôle de droit : l'appelant l'a déjà fait.
     */
    public List<SyncCountry> countriesToSync(String onlyCountry) {
        List<SyncCountry> countries = references.receivingCountries();
        if (onlyCountry == null) {
            return countries;
        }
        List<SyncCountry> only =
                countries.stream().filter(c -> c.code().equalsIgnoreCase(onlyCountry)).toList();
        if (only.isEmpty()) {
            throw new DomainException(
                    ErrorCode.COUNTRY_NOT_FOUND,
                    "Pays inconnu, désactivé ou qui ne reçoit pas de transferts.");
        }
        return only;
    }

    /**
     * Exécute l'import. <b>Aucun contrôle de droit ici</b> : seuls {@link #sync} et le service des
     * tâches d'import (qui vérifient {@code REFERENCE_WRITE}) l'appellent.
     */
    public Report run(UUID actor, String onlyCountry, Progress progress) {
        Instant startedAt = clock.instant();
        List<String> warnings = new ArrayList<>();
        int banks = 0;
        int operatorsCreated = 0;
        int payerCodes = 0;
        int values = 0;
        int providerFailures = 0;

        List<SyncCountry> countries = countriesToSync(onlyCountry);
        progress.begin(countries.stream().map(SyncCountry::code).toList());
        for (SyncCountry country : countries) {
            progress.countryStarted(country.code());
            int banksBefore = banks;
            int operatorsBefore = operatorsCreated;
            int payerBefore = payerCodes;
            int warningsBefore = warnings.size();
            Result<List<PayoutService>> services =
                    gateway.payoutServices(country.code(), country.currency());
            if (!services.isOk()) {
                providerFailures++;
                warnings.add(
                        country.code() + " : services indisponibles (" + services.message() + ")");
                progress.countryFinished(
                        new CountryOutcome(
                                country.code(),
                                "FAILED",
                                0,
                                0,
                                0,
                                new ArrayList<>(
                                        warnings.subList(warningsBefore, warnings.size()))));
                continue;
            }
            Optional<PayoutService> bankService =
                    services.value().stream()
                            .filter(s -> BANK_SERVICE_CODE.equalsIgnoreCase(s.code()))
                            .findFirst();
            if (bankService.isPresent()) {
                Result<String> payer =
                        gateway.payerCode(
                                country.code(), bankService.get().code(), country.currency());
                if (payer.isOk()) {
                    references.setBankPayerCode(country.code(), payer.value());
                    payerCodes++;
                    Result<List<PayoutGateway.ProviderBank>> list =
                            gateway.banks(country.code(), payer.value());
                    if (list.isOk()) {
                        for (PayoutGateway.ProviderBank bank : list.value()) {
                            references.upsertBank(
                                    country.code(), bank.id(), bank.code(), bank.name());
                            banks++;
                        }
                    } else {
                        warnings.add(
                                country.code()
                                        + " : liste des banques indisponible ("
                                        + list.message()
                                        + ")");
                    }
                } else {
                    warnings.add(
                            country.code()
                                    + " : code payeur bancaire indisponible ("
                                    + payer.message()
                                    + ")");
                }
            }
            // Les services de Wace autres que la banque deviennent des opérateurs mobiles s'ils
            // manquent :
            // c'est ce qui permet de compléter un pays nouvellement ajouté.
            for (PayoutService service : services.value()) {
                if (!BANK_SERVICE_CODE.equalsIgnoreCase(service.code())
                        && service.name() != null
                        && references.ensureOperator(
                                country.code(), service.code(), service.name().strip())) {
                    operatorsCreated++;
                }
            }
            for (OperatorRef operator : references.operatorsOf(country.code())) {
                Optional<PayoutService> match =
                        services.value().stream().filter(s -> matches(operator, s)).findFirst();
                if (match.isEmpty()) {
                    warnings.add(
                            country.code()
                                    + " : opérateur « "
                                    + operator.name()
                                    + " » sans service correspondant chez Wace (code payeur à"
                                    + " renseigner à la main)");
                    continue;
                }
                Result<String> payer =
                        gateway.payerCode(country.code(), match.get().code(), country.currency());
                if (payer.isOk()) {
                    references.setOperatorPayerCode(operator.id(), payer.value());
                    payerCodes++;
                } else {
                    warnings.add(
                            country.code()
                                    + " : code payeur de « "
                                    + operator.name()
                                    + " » indisponible ("
                                    + payer.message()
                                    + ")");
                }
            }
            List<String> own = new ArrayList<>(warnings.subList(warningsBefore, warnings.size()));
            progress.countryFinished(
                    new CountryOutcome(
                            country.code(),
                            own.isEmpty() ? "OK" : "PARTIAL",
                            banks - banksBefore,
                            operatorsCreated - operatorsBefore,
                            payerCodes - payerBefore,
                            own));
        }

        progress.labelsStarted();
        int labelWarningsBefore = warnings.size();
        values += importLabels("RELATIONSHIP", "ALL", gateway.relations(), warnings);
        for (String pair : PAIRS) {
            values += importLabels("SOURCE_OF_FUNDS", pair, gateway.originFunds(pair), warnings);
            values += importLabels("TRANSFER_PURPOSE", pair, gateway.reasons(pair), warnings);
        }
        progress.labelsFinished(
                values, new ArrayList<>(warnings.subList(labelWarningsBefore, warnings.size())));
        if (!countries.isEmpty() && providerFailures == countries.size() && values == 0) {
            throw new DomainException(
                    ErrorCode.PROVIDER_UNAVAILABLE,
                    "Wace est injoignable : aucun référentiel importé.");
        }
        audit.recordAs(
                actor,
                "REFERENCE_SYNCED",
                "REFERENCE",
                "wace",
                null,
                "{\"country\":"
                        + (onlyCountry == null
                                ? "null"
                                : "\"" + onlyCountry.toUpperCase(Locale.ROOT) + "\"")
                        + ",\"banks\":"
                        + banks
                        + ",\"operators\":"
                        + operatorsCreated
                        + ",\"payerCodes\":"
                        + payerCodes
                        + ",\"referenceValues\":"
                        + values
                        + ",\"warnings\":"
                        + warnings.size()
                        + "}");
        return new Report(startedAt, banks, operatorsCreated, payerCodes, values, warnings);
    }

    private int importLabels(
            String category,
            String pair,
            Result<Map<String, String>> result,
            List<String> warnings) {
        if (!result.isOk()) {
            warnings.add(
                    category + "/" + pair + " : liste indisponible (" + result.message() + ")");
            return 0;
        }
        result.value()
                .forEach(
                        (key, label) ->
                                references.upsertReferenceValue(category, pair, key, label, key));
        return result.value().size();
    }

    private static boolean matches(OperatorRef operator, PayoutService service) {
        return (operator.shortCode() != null
                        && operator.shortCode().equalsIgnoreCase(service.code()))
                || (service.name() != null
                        && service.name()
                                .trim()
                                .toUpperCase(Locale.ROOT)
                                .equals(operator.name().trim().toUpperCase(Locale.ROOT)));
    }
}
