package com.wtc.shared.web;

import com.wtc.shared.config.WtcProperties;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import java.net.URI;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Fabrique de réponses RFC 9457 homogènes (type, titre, statut, code applicatif, identifiant de
 * requête).
 */
@Component
public class Problems {

    private final String baseUri;

    public Problems(WtcProperties properties) {
        this.baseUri = properties.problem().baseUri();
    }

    public ProblemDetail of(ErrorCode code, String detail) {
        return of(code, detail, List.of());
    }

    public ProblemDetail of(ErrorCode code, String detail, List<FieldViolation> violations) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(baseUri + code.slug()));
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        String requestId = RequestIdFilter.current();
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        if (!violations.isEmpty()) {
            problem.setProperty("errors", violations);
        }
        return problem;
    }
}
