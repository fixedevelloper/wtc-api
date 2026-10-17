package com.wtc.shared.security;

import com.wtc.shared.config.WtcProperties;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Réponses 401 et 403 des filtres de sécurité au format {@code application/problem+json}. Les
 * filtres s'exécutent avant Spring MVC : {@code GlobalExceptionHandler} n'est pas appelé, d'où ce
 * composant.
 */
@Component
class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private final JsonMapper json;
    private final String baseUri;

    ProblemSecurityHandlers(JsonMapper json, WtcProperties properties) {
        this.json = json;
        this.baseUri = properties.problem().baseUri();
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        write(response, ErrorCode.UNAUTHENTICATED, "Authentification requise ou invalide.");
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        write(response, ErrorCode.ACCESS_DENIED, "Droit insuffisant pour cette opération.");
    }

    private void write(HttpServletResponse response, ErrorCode code, String detail)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", baseUri + code.slug());
        body.put("title", code.title());
        body.put("status", code.status().value());
        body.put("detail", detail);
        body.put("code", code.name());
        String requestId = RequestIdFilter.current();
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        response.setStatus(code.status().value());
        response.setContentType(
                PROBLEM_JSON.toString()); // JSON est toujours en UTF-8 : pas de paramètre charset
        response.getOutputStream()
                .write(json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }
}
