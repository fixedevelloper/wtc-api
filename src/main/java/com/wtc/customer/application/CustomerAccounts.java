package com.wtc.customer.application;

import com.wtc.customer.application.port.CustomerAccountRepository;
import com.wtc.customer.application.port.CustomerRepository;
import com.wtc.reference.application.ReferenceService;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Porte d'entrée du module client pour les autres modules. L'identité y crée la fiche d'un nouveau
 * compte ; elle la relit pour {@code /me}.
 */
@Service
public class CustomerAccounts {

    private final CustomerAccountRepository customers;
    private final CustomerRepository customerRecords;
    private final ReferenceService references;

    public CustomerAccounts(
            CustomerAccountRepository customers,
            CustomerRepository customerRecords,
            ReferenceService references) {
        this.customers = customers;
        this.customerRecords = customerRecords;
        this.references = references;
    }

    @Transactional
    public UUID registerIndividual(
            long userId,
            String firstName,
            String lastName,
            String phone,
            String email,
            String countryCode) {
        if (!references.isEnabledCountry(countryCode)) {
            throw new DomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Pays non pris en charge.",
                    List.of(
                            new FieldViolation(
                                    "countryCode",
                                    "UnsupportedCountry",
                                    "Ce pays n'est pas disponible.")));
        }
        return customers.insertIndividual(userId, firstName, lastName, phone, email, countryCode);
    }

    /**
     * @throws DomainException {@code CUSTOMER_NOT_FOUND} si le client n'existe pas
     */
    @Transactional(readOnly = true)
    public void requireExists(UUID customerId) {
        if (customerRecords.findByPublicId(customerId).isEmpty()) {
            throw new DomainException(ErrorCode.CUSTOMER_NOT_FOUND, "Client introuvable.");
        }
    }

    /** Coordonnées du client ; vide si le client n'existe pas. */
    @Transactional(readOnly = true)
    public Optional<CustomerContact> contactOf(UUID customerId) {
        return customerRecords
                .findByPublicId(customerId)
                .map(
                        r -> {
                            CustomerProfile p = r.profile();
                            return new CustomerContact(
                                    r.publicId(),
                                    CustomerSupport.displayName(
                                            p.kind(),
                                            p.firstName(),
                                            p.lastName(),
                                            p.businessName()),
                                    p.email(),
                                    p.phone());
                        });
    }

    /** Situation d'un client pour l'envoi d'un transfert (statut du compte et KYC). */
    @Transactional(readOnly = true)
    public CustomerForTransfer forTransfer(UUID customerId) {
        var record =
                customerRecords
                        .findByPublicId(customerId)
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.CUSTOMER_NOT_FOUND,
                                                "Client introuvable."));
        CustomerProfile p = record.profile();
        return new CustomerForTransfer(
                record.id(),
                record.publicId(),
                CustomerSupport.displayName(
                        p.kind(), p.firstName(), p.lastName(), p.businessName()),
                p.countryCode(),
                p.kycStatus(),
                p.status());
    }

    /**
     * Devise du pays de résidence du client : celle de son portefeuille principal (hypothèse 17).
     */
    @Transactional(readOnly = true)
    public Optional<String> homeCurrency(UUID customerId) {
        return customerRecords
                .findByPublicId(customerId)
                .map(c -> c.profile().countryCode())
                .flatMap(references::currencyOf);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> customerIdOf(long userId) {
        return customers.findPublicIdByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Optional<CustomerProfile> profileOf(long userId) {
        return customers.findProfileByUserId(userId);
    }
}
