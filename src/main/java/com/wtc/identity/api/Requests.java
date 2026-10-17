package com.wtc.identity.api;

import com.wtc.identity.application.RequestMeta;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.web.ClientContext;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Extraction de l'origine de la requête et de l'identité portée par le jeton. */
final class Requests {

    private Requests() {}

    static RequestMeta meta(ClientContext client) {
        return new RequestMeta(client.ip(), client.userAgent());
    }

    static UUID userId(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED, "Jeton sans sujet.");
        }
        return UUID.fromString(subject);
    }

    static UUID sessionId(Jwt jwt) {
        String sid = jwt.getClaimAsString("sid");
        return sid == null ? new UUID(0, 0) : UUID.fromString(sid);
    }
}
