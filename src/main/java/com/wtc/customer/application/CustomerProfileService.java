package com.wtc.customer.application;

import com.wtc.customer.application.port.CustomerRepository;
import com.wtc.customer.application.port.CustomerRepository.CustomerRecord;
import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import com.wtc.shared.event.CustomerIdentityChanged;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Modification du profil par le client lui-même. */
@Service
public class CustomerProfileService {

    private final CustomerRepository customers;
    private final CustomerSupport support;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    CustomerProfileService(
            CustomerRepository customers,
            CustomerSupport support,
            AuditTrail audit,
            ApplicationEventPublisher events,
            Clock clock) {
        this.customers = customers;
        this.support = support;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public CustomerProfile update(UUID customerId, UUID actorUserId, ProfileChanges c) {
        CustomerRecord record = support.loadActive(customerId);
        CustomerProfile current = record.profile();

        if (c.email() != null && !c.email().equalsIgnoreCase(current.email())) {
            // L'adresse sert d'identifiant de connexion : la changer sans la vérifier permettrait
            // de voler un compte avec
            // un simple jeton volé. Reporté jusqu'à la mise en place d'une vérification par lien.
            throw new DomainException(
                    ErrorCode.EMAIL_CHANGE_UNSUPPORTED,
                    "Le changement d'adresse e-mail n'est pas encore pris en charge.",
                    List.of(
                            new FieldViolation(
                                    "email",
                                    "ChangeUnsupported",
                                    "Contactez le support pour changer d'adresse e-mail.")));
        }

        CustomerProfile merged =
                new CustomerProfile(
                        current.id(),
                        pick(c.kind(), current.kind()),
                        pick(c.firstName(), current.firstName()),
                        pick(c.lastName(), current.lastName()),
                        pick(c.businessName(), current.businessName()),
                        pick(c.businessType(), current.businessType()),
                        pick(c.businessRegisteredOn(), current.businessRegisteredOn()),
                        pick(c.birthDate(), current.birthDate()),
                        pick(c.nationality(), current.nationality()),
                        pick(c.occupation(), current.occupation()),
                        pick(c.gender(), current.gender()),
                        pick(c.civility(), current.civility()),
                        pick(c.addressLine(), current.addressLine()),
                        pick(c.city(), current.city()),
                        pick(c.countryCode(), current.countryCode()),
                        pick(c.phone(), current.phone()),
                        current.email(),
                        current.kycStatus(),
                        current.status(),
                        current.createdAt());

        List<String> changed = changedFields(current, merged);
        if (changed.isEmpty()) {
            return current;
        }
        if ("VERIFIED".equals(current.kycStatus())
                && changed.stream().anyMatch(CustomerProfileService::isIdentityField)) {
            throw new DomainException(
                    ErrorCode.KYC_LOCKED,
                    "Votre identité est vérifiée : contactez le support pour modifier votre nom,"
                            + " votre date de naissance ou votre type de compte.");
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        PartyRules.requireConsistent(
                merged.kind(),
                merged.firstName(),
                merged.lastName(),
                merged.businessName(),
                merged.birthDate(),
                merged.businessRegisteredOn(),
                today);
        support.requireEnabledCountry(merged.countryCode(), "countryCode");

        customers.saveProfile(record.id(), merged);
        if (record.userId() != null
                && (changed.contains("firstName")
                        || changed.contains("lastName")
                        || changed.contains("businessName")
                        || changed.contains("kind")
                        || changed.contains("phone"))) {
            events.publishEvent(
                    new CustomerIdentityChanged(
                            record.userId(),
                            CustomerSupport.displayName(
                                    merged.kind(),
                                    merged.firstName(),
                                    merged.lastName(),
                                    merged.businessName()),
                            merged.phone()));
        }
        // Seuls les noms des champs modifiés sont journalisés, pas leurs valeurs (données
        // personnelles).
        audit.recordAs(
                actorUserId,
                "CUSTOMER_PROFILE_UPDATED",
                "CUSTOMER",
                customerId.toString(),
                null,
                "{\"changedFields\":["
                        + String.join(",", changed.stream().map(f -> "\"" + f + "\"").toList())
                        + "]}");
        return customers.findByPublicId(customerId).orElseThrow().profile();
    }

    static boolean isIdentityField(String field) {
        return List.of(
                        "kind",
                        "firstName",
                        "lastName",
                        "businessName",
                        "birthDate",
                        "nationality",
                        "businessRegisteredOn")
                .contains(field);
    }

    private static List<String> changedFields(CustomerProfile a, CustomerProfile b) {
        List<String> changed = new ArrayList<>();
        check(changed, "kind", a.kind(), b.kind());
        check(changed, "firstName", a.firstName(), b.firstName());
        check(changed, "lastName", a.lastName(), b.lastName());
        check(changed, "businessName", a.businessName(), b.businessName());
        check(changed, "businessType", a.businessType(), b.businessType());
        check(changed, "businessRegisteredOn", a.businessRegisteredOn(), b.businessRegisteredOn());
        check(changed, "birthDate", a.birthDate(), b.birthDate());
        check(changed, "nationality", a.nationality(), b.nationality());
        check(changed, "occupation", a.occupation(), b.occupation());
        check(changed, "gender", a.gender(), b.gender());
        check(changed, "civility", a.civility(), b.civility());
        check(changed, "addressLine", a.addressLine(), b.addressLine());
        check(changed, "city", a.city(), b.city());
        check(changed, "countryCode", a.countryCode(), b.countryCode());
        check(changed, "phone", a.phone(), b.phone());
        return changed;
    }

    private static void check(List<String> changed, String name, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changed.add(name);
        }
    }

    private static <T> T pick(T proposed, T current) {
        return proposed != null ? proposed : current;
    }
}
