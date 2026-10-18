package com.wtc.reference.application;

import com.wtc.reference.application.port.CountryAdminRepository;
import com.wtc.reference.application.port.CountryAdminRepository.NewCountry;
import com.wtc.reference.domain.City;
import com.wtc.reference.domain.Country;
import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ajout d'un pays (permission {@code REFERENCE_WRITE}). Un pays qui reçoit des transferts est
 * ensuite complété (code payeur, banques, opérateurs) par l'import Wace.
 */
@Service
public class CountryAdminService {

    private final CountryAdminRepository countries;
    private final AuditTrail audit;

    CountryAdminService(CountryAdminRepository countries, AuditTrail audit) {
        this.countries = countries;
        this.audit = audit;
    }

    public record Input(
            String code,
            String iso3,
            String name,
            String currencyCode,
            String callingCode,
            String flagUrl,
            Long pricingZoneId,
            boolean canSend,
            boolean canReceive) {}

    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    @Transactional
    public Country create(UUID actor, Input in) {
        String code = in.code().strip().toUpperCase(Locale.ROOT);
        String iso3 = in.iso3().strip().toUpperCase(Locale.ROOT);
        if (in.pricingZoneId() != null && !countries.zoneExists(in.pricingZoneId())) {
            throw new DomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Zone tarifaire inconnue.",
                    List.of(
                            new FieldViolation(
                                    "pricingZoneId", "UnknownZone", "Zone tarifaire inconnue.")));
        }
        Country created;
        try {
            if (countries.existsByCodeOrIso3(code, iso3)) {
                throw exists();
            }
            created =
                    countries.insert(
                            new NewCountry(
                                    code,
                                    iso3,
                                    in.name().strip(),
                                    in.currencyCode().strip().toUpperCase(Locale.ROOT),
                                    blankToNull(in.callingCode()),
                                    blankToNull(in.flagUrl()),
                                    in.pricingZoneId(),
                                    in.canSend(),
                                    in.canReceive()));
        } catch (DuplicateKeyException duplicate) {
            throw exists();
        }
        audit.recordAs(
                actor,
                "COUNTRY_CREATED",
                "COUNTRY",
                code,
                null,
                "{\"code\":\""
                        + code
                        + "\",\"canSend\":"
                        + in.canSend()
                        + ",\"canReceive\":"
                        + in.canReceive()
                        + "}");
        return created;
    }

    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    @Transactional
    public City addCity(UUID actor, String countryCode, String name) {
        String code = countryCode.strip().toUpperCase(Locale.ROOT);
        String city = name.strip();
        if (!countries.countryExists(code)) {
            throw new DomainException(ErrorCode.COUNTRY_NOT_FOUND, "Pays introuvable.");
        }
        City created;
        try {
            if (countries.cityExists(code, city)) {
                throw cityExists();
            }
            created = countries.insertCity(code, city);
        } catch (DuplicateKeyException duplicate) {
            throw cityExists();
        }
        audit.recordAs(
                actor,
                "CITY_CREATED",
                "CITY",
                String.valueOf(created.id()),
                null,
                "{\"country\":\"" + code + "\"}");
        return created;
    }

    private static DomainException cityExists() {
        return new DomainException(ErrorCode.CITY_EXISTS, "Cette ville existe déjà dans ce pays.");
    }

    private static DomainException exists() {
        return new DomainException(ErrorCode.COUNTRY_EXISTS, "Ce pays existe déjà.");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
