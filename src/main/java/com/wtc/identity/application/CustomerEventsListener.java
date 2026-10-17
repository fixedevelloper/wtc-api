package com.wtc.identity.application;

import com.wtc.identity.application.port.RefreshTokenStore;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.UserStatus;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.event.CustomerIdentityChanged;
import com.wtc.shared.event.CustomerStatusChanged;
import java.time.Clock;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Réagit aux changements d'un client pour garder son compte d'accès cohérent. Les écouteurs
 * s'exécutent dans la transaction de l'émetteur : si l'un échoue (téléphone déjà pris), toute la
 * modification est annulée.
 */
@Component
class CustomerEventsListener {

    private final UserRepository users;
    private final RefreshTokenStore refreshTokens;
    private final Clock clock;

    CustomerEventsListener(UserRepository users, RefreshTokenStore refreshTokens, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @EventListener
    void onStatusChanged(CustomerStatusChanged event) {
        if (event.userId() == null) {
            return; // client créé par le back-office : pas de compte d'accès
        }
        if ("ACTIVE".equals(event.status())) {
            users.setStatus(event.userId(), UserStatus.ACTIVE);
        } else {
            users.setStatus(event.userId(), UserStatus.SUSPENDED);
            refreshTokens.revokeAllOfUser(
                    event.userId(), clock.instant()); // sessions fermées tout de suite
        }
    }

    @EventListener
    void onIdentityChanged(CustomerIdentityChanged event) {
        if (users.phoneUsedByAnother(event.phoneE164(), event.userId())) {
            throw new DomainException(
                    ErrorCode.PHONE_ALREADY_REGISTERED, "Ce numéro de téléphone est déjà utilisé.");
        }
        users.updateContact(event.userId(), event.displayName(), event.phoneE164());
    }
}
