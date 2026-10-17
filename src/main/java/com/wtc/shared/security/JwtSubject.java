package com.wtc.shared.security;

import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Identifiant public du compte porté par le jeton ({@code sub}). */
public final class JwtSubject {

    private JwtSubject() {}

    public static UUID userId(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "Jeton sans sujet.");
        }
        return UUID.fromString(subject);
    }
}
