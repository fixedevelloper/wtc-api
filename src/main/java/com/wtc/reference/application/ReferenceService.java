package com.wtc.reference.application;

import com.wtc.reference.application.port.ClientRequirementsRepository;
import com.wtc.reference.application.port.CountryRepository;
import com.wtc.reference.application.port.PayoutCatalogRepository;
import com.wtc.reference.application.port.SettingsRepository;
import com.wtc.reference.domain.Bank;
import com.wtc.reference.domain.City;
import com.wtc.reference.domain.ClientRequirement;
import com.wtc.reference.domain.Country;
import com.wtc.reference.domain.MobileOperator;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cas d'usage de lecture des référentiels publics. */
@Service
@Transactional(readOnly = true)
public class ReferenceService {

    private final CountryRepository countries;
    private final ClientRequirementsRepository requirements;
    private final PayoutCatalogRepository catalog;
    private final SettingsRepository settings;
    private final tools.jackson.databind.json.JsonMapper json;

    public ReferenceService(
            CountryRepository countries,
            ClientRequirementsRepository requirements,
            PayoutCatalogRepository catalog,
            SettingsRepository settings,
            tools.jackson.databind.json.JsonMapper json) {
        this.countries = countries;
        this.requirements = requirements;
        this.catalog = catalog;
        this.settings = settings;
        this.json = json;
    }

    public List<Country> listCountries() {
        return countries.findAllEnabled();
    }

    public List<City> listCities(String countryCode) {
        String code = countryCode.toUpperCase(Locale.ROOT);
        countries
                .findEnabledByCode(code)
                .orElseThrow(
                        () ->
                                new DomainException(
                                        ErrorCode.COUNTRY_NOT_FOUND, "Pays inconnu : " + code));
        return countries.findCitiesByCountryCode(code);
    }

    /** Le pays est actif et accepte des paiements entrants (destination d'un transfert). */
    public boolean canReceive(String countryCode) {
        return countryCode != null
                && countries
                        .findEnabledByCode(countryCode.toUpperCase(Locale.ROOT))
                        .map(Country::canReceive)
                        .orElse(false);
    }

    /** Devise d'un pays actif (vide si le pays est inconnu ou désactivé). */
    public Optional<String> currencyOf(String countryCode) {
        return countryCode == null
                ? Optional.empty()
                : countries
                        .findEnabledByCode(countryCode.toUpperCase(Locale.ROOT))
                        .map(Country::currencyCode);
    }

    public boolean isEnabledCountry(String countryCode) {
        return countryCode != null
                && countries.findEnabledByCode(countryCode.toUpperCase(Locale.ROOT)).isPresent();
    }

    public List<BankView> listBanks(String countryCode) {
        String code = requireCountry(countryCode);
        return catalog.findEnabledBanks(code).stream().map(ReferenceService::view).toList();
    }

    public List<MobileOperatorView> listMobileOperators(String countryCode) {
        String code = requireCountry(countryCode);
        return catalog.findEnabledOperators(code).stream().map(ReferenceService::view).toList();
    }

    public Optional<BankView> findBank(long id) {
        return catalog.findEnabledBank(id).map(ReferenceService::view);
    }

    public Optional<MobileOperatorView> findMobileOperator(long id) {
        return catalog.findEnabledOperator(id).map(ReferenceService::view);
    }

    /**
     * Routage d'un envoi bancaire vers Wace ; vide si une des données Wace manque (envoi
     * impossible).
     */
    public Optional<BankRouting> bankRouting(long bankId, String countryCode) {
        Optional<BankView> bank = findBank(bankId);
        Optional<String> payer = catalog.bankPayerCode(countryCode.toUpperCase(Locale.ROOT));
        Optional<String> waceId = catalog.waceBankId(bankId);
        if (bank.isEmpty() || payer.isEmpty() || waceId.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    new BankRouting(
                            payer.get(),
                            bank.get().name(),
                            Long.valueOf(waceId.get()),
                            bank.get().shortCode()));
        } catch (NumberFormatException e) {
            return Optional.empty(); // identifiant Wace non numérique : référentiel à corriger
        }
    }

    public Optional<String> operatorPayerCode(long operatorId) {
        return catalog.operatorPayerCode(operatorId);
    }

    /**
     * Valeur envoyée à Wace pour un motif, une origine des fonds ou une relation (catégories : voir
     * la base).
     */
    public Optional<String> waceReferenceCode(String category, String partyTypePair, String code) {
        return catalog.waceReferenceCode(category, partyTypePair, code);
    }

    /**
     * Plafond par transfert (paramètre {@code transfer.max_amount_per_transfer}), exprimé dans la
     * devise par défaut.
     */
    public Optional<java.math.BigDecimal> maxAmountPerTransfer() {
        return settings.find("transfer.max_amount_per_transfer")
                .map(v -> json.readTree(v.valueJson()).decimalValue());
    }

    /** Devise par défaut de la plateforme (paramètre {@code wallet.default_currency}). */
    public String defaultCurrency() {
        return settings.find("wallet.default_currency")
                .map(v -> json.readTree(v.valueJson()).asString())
                .orElse("XAF");
    }

    private String requireCountry(String countryCode) {
        String code = countryCode.toUpperCase(Locale.ROOT);
        countries
                .findEnabledByCode(code)
                .orElseThrow(
                        () ->
                                new DomainException(
                                        ErrorCode.COUNTRY_NOT_FOUND, "Pays inconnu : " + code));
        return code;
    }

    public List<ClientRequirement> clientRequirements() {
        return requirements.findAll();
    }

    private static BankView view(Bank b) {
        return new BankView(b.id(), b.countryCode(), b.name(), b.shortCode());
    }

    private static MobileOperatorView view(MobileOperator o) {
        return new MobileOperatorView(
                o.id(),
                o.countryCode(),
                o.name(),
                o.shortCode(),
                o.supportsPayout(),
                o.supportsPayin());
    }
}
