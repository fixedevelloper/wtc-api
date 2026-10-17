package com.wtc.customer.application.port;

import com.wtc.customer.application.AdminCustomerSummary;
import com.wtc.customer.application.CustomerProfile;
import com.wtc.customer.application.Paged;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository {

    record CustomerRecord(long id, UUID publicId, Long userId, CustomerProfile profile) {}

    Optional<CustomerRecord> findByPublicId(UUID publicId);

    /** Enregistre les champs modifiables du profil (les valeurs fusionnées par le service). */
    void saveProfile(long id, CustomerProfile merged);

    void setStatus(long id, String status);

    void setKycStatus(long id, String status);

    Paged<AdminCustomerSummary> search(
            String search, int page, int size, String sortField, boolean ascending);
}
