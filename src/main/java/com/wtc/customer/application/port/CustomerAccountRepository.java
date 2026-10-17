package com.wtc.customer.application.port;

import com.wtc.customer.application.CustomerProfile;
import java.util.Optional;
import java.util.UUID;

public interface CustomerAccountRepository {

    /** Crée un client particulier rattaché à un compte d'accès. */
    UUID insertIndividual(
            long userId,
            String firstName,
            String lastName,
            String phone,
            String email,
            String countryCode);

    Optional<UUID> findPublicIdByUserId(long userId);

    Optional<CustomerProfile> findProfileByUserId(long userId);
}
