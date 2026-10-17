package com.wtc.customer.application;

import com.wtc.customer.application.port.CustomerRepository;
import com.wtc.customer.application.port.CustomerRepository.CustomerRecord;
import com.wtc.reference.application.ReferenceService;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Chargement et garde-fous communs aux services du module client. */
@Component
class CustomerSupport {

    private final CustomerRepository customers;
    private final ReferenceService references;

    CustomerSupport(CustomerRepository customers, ReferenceService references) {
        this.customers = customers;
        this.references = references;
    }

    CustomerRecord load(UUID customerId) {
        return customers
                .findByPublicId(customerId)
                .orElseThrow(
                        () ->
                                new DomainException(
                                        ErrorCode.CUSTOMER_NOT_FOUND, "Client introuvable."));
    }

    /**
     * Un client suspendu ou clos peut consulter mais plus rien modifier (son jeton reste valable
     * jusqu'à 15 minutes).
     */
    CustomerRecord loadActive(UUID customerId) {
        CustomerRecord record = load(customerId);
        if (!"ACTIVE".equals(record.profile().status())) {
            throw new DomainException(
                    ErrorCode.CUSTOMER_NOT_ACTIVE, "Votre compte n'est pas actif.");
        }
        return record;
    }

    void requireEnabledCountry(String countryCode, String field) {
        if (countryCode != null && !references.isEnabledCountry(countryCode)) {
            throw new DomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Pays non pris en charge.",
                    List.of(
                            new FieldViolation(
                                    field, "UnsupportedCountry", "Ce pays n'est pas disponible.")));
        }
    }

    static String displayName(String kind, String first, String last, String business) {
        if ("BUSINESS".equals(kind) && business != null && !business.isBlank()) {
            return business;
        }
        return ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
    }
}
