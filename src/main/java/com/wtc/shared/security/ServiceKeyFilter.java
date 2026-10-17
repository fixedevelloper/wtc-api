package com.wtc.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentifie les appels du chatbot WhatsApp par la clé {@code X-Service-Key} (routes {@code
 * /api/web-tokens/**} uniquement). La clé est comparée en temps constant, par empreinte ; sans clé
 * configurée, aucune requête n'est authentifiée (routes fermées). Une clé absente ou fausse laisse
 * la requête anonyme : elle sera refusée en 401 comme toute requête non authentifiée.
 */
public final class ServiceKeyFilter extends OncePerRequestFilter {

    public static final String AUTHORITY = "SERVICE_WHATSAPP";
    static final String HEADER = "X-Service-Key";

    private final byte[] expected;

    public ServiceKeyFilter(String serviceKey) {
        this.expected =
                serviceKey == null || serviceKey.isBlank()
                        ? null
                        : Tokens.sha256(serviceKey).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/web-tokens/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (expected != null
                && presented != null
                && MessageDigest.isEqual(
                        expected, Tokens.sha256(presented).getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(
                                    "service:whatsapp",
                                    null,
                                    List.of(new SimpleGrantedAuthority(AUTHORITY))));
        }
        chain.doFilter(request, response);
    }
}
