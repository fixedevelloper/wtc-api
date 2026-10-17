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
            int payerCodes,
            int referenceValues,
            List<String> warnings) {
        public Report {
            warnings = List.copyOf(warnings);
        }
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

    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    public Report sync(UUID actor) {
        Instant startedAt = clock.instant();
        List<String> warnings = new ArrayList<>();
        int banks = 0;
        int payerCodes = 0;
        int values = 0;
        int providerFailures = 0;

        List<SyncCountry> countries = references.receivingCountries();
        for (SyncCountry country : countries) {
            Result<List<PayoutService>> services =
                    gateway.payoutServices(country.code(), country.currency());
            if (!services.isOk()) {
                providerFailures++;
                warnings.add(
                        country.code() + " : services indisponibles (" + services.message() + ")");
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
        }

        values += importLabels("RELATIONSHIP", "ALL", gateway.relations(), warnings);
        for (String pair : PAIRS) {
            values += importLabels("SOURCE_OF_FUNDS", pair, gateway.originFunds(pair), warnings);
            values += importLabels("TRANSFER_PURPOSE", pair, gateway.reasons(pair), warnings);
        }
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
                "{\"banks\":"
                        + banks
                        + ",\"payerCodes\":"
                        + payerCodes
                        + ",\"referenceValues\":"
                        + values
                        + ",\"warnings\":"
                        + warnings.size()
                        + "}");
        return new Report(startedAt, banks, payerCodes, values, warnings);
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
