package com.wtc.customer.application.port;

import com.wtc.customer.application.Paged;
import com.wtc.customer.application.PartyData;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PartyRepository {

    record PartyRecord(
            long id,
            UUID publicId,
            long customerId,
            UUID customerPublicId,
            String customerName,
            String role,
            PartyData data,
            byte[] idNumberEncrypted,
            String idNumberLast4,
            Instant createdAt) {

        public PartyRecord {
            idNumberEncrypted = idNumberEncrypted == null ? null : idNumberEncrypted.clone();
        }

        @Override
        public byte[] idNumberEncrypted() {
            return idNumberEncrypted == null ? null : idNumberEncrypted.clone();
        }
    }

    void insert(
            UUID publicId,
            long customerId,
            String role,
            PartyData data,
            byte[] idNumberEncrypted,
            String idNumberLast4);

    /** Les parties archivées sont invisibles : elles n'existent plus pour les clients. */
    Optional<PartyRecord> find(UUID publicId);

    /**
     * @param replaceIdNumber faux = conserver le numéro de pièce déjà enregistré
     */
    void update(
            long id,
            PartyData data,
            boolean replaceIdNumber,
            byte[] idNumberEncrypted,
            String idNumberLast4);

    /** Archive la partie et toutes ses destinations. */
    void archive(long id, Instant now);

    Paged<PartyRecord> listOfCustomer(
            long customerId,
            String role,
            String search,
            int page,
            int size,
            String sortField,
            boolean ascending);

    Paged<PartyRecord> adminList(
            String role,
            UUID customerPublicId,
            String search,
            int page,
            int size,
            String sortField,
            boolean ascending);
}
