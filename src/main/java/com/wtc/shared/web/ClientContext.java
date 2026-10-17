package com.wtc.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Adresse IP et agent utilisateur de la requête en cours (pour l'audit et la limitation de débit).
 * L'IP est celle vue par le serveur après traitement des en-têtes {@code X-Forwarded-*} par le
 * reverse proxy de confiance.
 */
@Component
public class ClientContext {

    private static final int MAX_USER_AGENT = 300;

    public String ip() {
        HttpServletRequest request = current();
        return request == null ? null : request.getRemoteAddr();
    }

    public String userAgent() {
        HttpServletRequest request = current();
        String value = request == null ? null : request.getHeader("User-Agent");
        return value == null ? null : value.substring(0, Math.min(value.length(), MAX_USER_AGENT));
    }

    private static HttpServletRequest current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
}
