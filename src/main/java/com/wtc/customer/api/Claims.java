package com.wtc.customer.api;

import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Identité portée par le jeton : jamais lue dans un paramètre de la requête. */
final class Claims {

    private Claims() {}

    /** Identifiant du compte (sujet du jeton). */
    static UUID userId(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "Jeton sans sujet.");
        }
        return UUID.fromString(subject);
    }

    /** Fiche client du compte ; absente pour le personnel, qui n'a pas de fiche. */
    static UUID customerId(Jwt jwt) {
        String id = jwt.getClaimAsString("customer_id");
        if (id == null) {
            throw new DomainException(
                    ErrorCode.ACCESS_DENIED, "Ce compte n'a pas de fiche client.");
        }
        return UUID.fromString(id);
    }
}
