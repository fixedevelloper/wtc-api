package com.wtc.customer.api;

import com.wtc.customer.application.DestinationView;
import com.wtc.customer.application.PartyData;
import com.wtc.customer.application.PartyView;
import com.wtc.reference.application.BankView;
import com.wtc.reference.application.MobileOperatorView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Réponses de l'API pour les expéditeurs, bénéficiaires et leurs destinations. */
final class PartyResponses {

    private PartyResponses() {}

    record IdDocument(String type, String numberMasked, LocalDate expiresOn) {}

    record CustomerRef(UUID id, String displayName) {}

    record BankRef(long id, String name, String shortCode) {}

    record OperatorRef(
            long id,
            String name,
            String shortCode,
            boolean supportsPayout,
            boolean supportsPayin) {}

    record Destination(
            UUID id,
            String kind,
            String countryCode,
            BankRef bank,
            OperatorRef mobileOperator,
            String accountHolderName,
            String accountNumberMasked,
            String swiftCode,
            String ifscCode,
            String routingNumber,
            boolean isDefault) {

        static Destination from(DestinationView v) {
            BankView bank = v.bank();
            MobileOperatorView operator = v.mobileOperator();
            return new Destination(
                    v.id(),
                    v.kind(),
                    v.countryCode(),
                    bank == null ? null : new BankRef(bank.id(), bank.name(), bank.shortCode()),
                    operator == null
                            ? null
                            : new OperatorRef(
                                    operator.id(),
                                    operator.name(),
                                    operator.shortCode(),
                                    operator.supportsPayout(),
                                    operator.supportsPayin()),
                    v.accountHolderName(),
                    v.accountNumberMasked(),
                    v.swiftCode(),
                    v.ifscCode(),
                    v.routingNumber(),
                    v.isDefault());
        }
    }

    record Party(
            UUID id,
            String role,
            String kind,
            String firstName,
            String lastName,
            String businessName,
            String businessType,
            LocalDate businessRegisteredOn,
            LocalDate birthDate,
            String nationality,
            String occupation,
            String gender,
            String civility,
            String addressLine,
            String city,
            String countryCode,
            String phone,
            String email,
            IdDocument idDocument,
            boolean pep,
            Instant createdAt,
            CustomerRef customer,
            List<Destination> destinations) {

        static Party from(PartyView v) {
            return from(v, null);
        }

        /** Vue d'administration : ajoute le client propriétaire et les destinations. */
        static Party from(PartyView v, List<DestinationView> destinations) {
            PartyData d = v.data();
            IdDocument id =
                    d.idDocumentType() == null
                                    && v.idDocumentNumberMasked() == null
                                    && d.idDocumentExpiresOn() == null
                            ? null
                            : new IdDocument(
                                    d.idDocumentType(),
                                    v.idDocumentNumberMasked(),
                                    d.idDocumentExpiresOn());
            return new Party(
                    v.id(),
                    v.role(),
                    d.kind(),
                    d.firstName(),
                    d.lastName(),
                    d.businessName(),
                    d.businessType(),
                    d.businessRegisteredOn(),
                    d.birthDate(),
                    d.nationality(),
                    d.occupation(),
                    d.gender(),
                    d.civility(),
                    d.addressLine(),
                    d.city(),
                    d.countryCode(),
                    d.phone(),
                    d.email(),
                    id,
                    d.pep(),
                    v.createdAt(),
                    v.customerId() == null
                            ? null
                            : new CustomerRef(v.customerId(), v.customerName()),
                    destinations == null
                            ? null
                            : destinations.stream().map(Destination::from).toList());
        }
    }
}
