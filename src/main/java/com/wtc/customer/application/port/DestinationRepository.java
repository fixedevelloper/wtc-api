package com.wtc.customer.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DestinationRepository {

    record DestinationRecord(
            long id,
            UUID publicId,
            long partyId,
            String kind,
            String countryCode,
            Long bankId,
            Long mobileOperatorId,
            String accountHolderName,
            byte[] accountNumberEncrypted,
            String accountNumberMasked,
            String swiftCode,
            String ifscCode,
            String routingNumber,
            boolean isDefault) {

        public DestinationRecord {
            accountNumberEncrypted =
                    accountNumberEncrypted == null ? null : accountNumberEncrypted.clone();
        }

        @Override
        public byte[] accountNumberEncrypted() {
            return accountNumberEncrypted == null ? null : accountNumberEncrypted.clone();
        }
    }

    void insert(DestinationRecord record);

    Optional<DestinationRecord> find(long partyId, UUID publicId);

    /** Destination active appartenant (via son bénéficiaire) à ce client. */
    Optional<DestinationRecord> findOfCustomer(long customerId, UUID publicId);

    List<DestinationRecord> list(long partyId);

    void update(DestinationRecord record);

    void archive(long id, Instant now);

    void clearDefault(long partyId);

    /** Rend « par défaut » la destination active la plus récente, s'il en reste une. */
    void promoteMostRecent(long partyId);
}
