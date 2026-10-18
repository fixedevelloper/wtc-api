package com.wtc.customer.application;

import com.wtc.customer.application.port.CustomerRepository;
import com.wtc.customer.application.port.CustomerRepository.CustomerRecord;
import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import com.wtc.shared.event.CustomerStatusChanged;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Consultation et gestion des clients par le back-office. */
@Service
public class AdminCustomerService {

    private static final Set<String> STATUSES = Set.of("ACTIVE", "SUSPENDED", "CLOSED");

    private final CustomerRepository customers;
    private final CustomerSupport support;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;

    AdminCustomerService(
            CustomerRepository customers,
            CustomerSupport support,
            AuditTrail audit,
            ApplicationEventPublisher events,
            JsonMapper json) {
        this.customers = customers;
        this.support = support;
        this.audit = audit;
        this.events = events;
        this.json = json;
    }

    @PreAuthorize("hasAuthority('CUSTOMER_READ')")
    @Transactional(readOnly = true)
    public Paged<AdminCustomerSummary> list(
            String search,
            String status,
            String kycStatus,
            int page,
            int size,
            String sortField,
            boolean ascending) {
        return customers.search(search, status, kycStatus, page, size, sortField, ascending);
    }

    @PreAuthorize("hasAuthority('CUSTOMER_READ')")
    @Transactional(readOnly = true)
    public CustomerProfile get(UUID customerId) {
        return support.load(customerId).profile();
    }

    /**
     * Suspend, rétablit ou clôt un client. La clôture est définitive. Le compte d'accès suit (voir
     * {@link CustomerStatusChanged}) : ses sessions sont fermées immédiatement.
     */
    @PreAuthorize("hasAuthority('CUSTOMER_WRITE')")
    @Transactional
    public CustomerProfile updateStatus(
            UUID actorId, UUID customerId, String status, String reason) {
        if (!STATUSES.contains(status)) {
            throw new DomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Statut invalide.",
                    List.of(
                            new FieldViolation(
                                    "status", "Invalid", "ACTIVE, SUSPENDED ou CLOSED.")));
        }
        CustomerRecord record = support.load(customerId);
        String before = record.profile().status();
        if ("CLOSED".equals(before) && !"CLOSED".equals(status)) {
            throw new DomainException(
                    ErrorCode.CONFLICT, "Un client clos ne peut pas être rétabli.");
        }
        if (before.equals(status)) {
            return record.profile();
        }
        customers.setStatus(record.id(), status);
        events.publishEvent(new CustomerStatusChanged(customerId, record.userId(), status));
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("status", status);
        if (reason != null && !reason.isBlank()) {
            change.put("reason", reason.strip());
        }
        audit.recordAs(
                actorId,
                "CUSTOMER_STATUS_CHANGED",
                "CUSTOMER",
                customerId.toString(),
                "{\"status\":\"" + before + "\"}",
                json.writeValueAsString(change));
        return customers.findByPublicId(customerId).orElseThrow().profile();
    }
}
