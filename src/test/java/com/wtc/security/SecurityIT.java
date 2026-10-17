package com.wtc.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wtc.support.AbstractIT;
import com.wtc.support.TestJwt;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Sécurité HTTP : validation réelle des jetons RS256, 401/403 en problem+json, CORS, en-têtes,
 * absence de session.
 */
@TestPropertySource(properties = "wtc.test.probe=true")
class SecurityIT extends AbstractIT {

    private static final String PROTECTED = "/api/_probe/authenticated";
    private static final String NEEDS_PERMISSION = "/api/admin/_probe/transfers";

    @Autowired MockMvc mvc;

    private ResultActions call(String path, String token) throws Exception {
        var request = get(path).secure(true);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request);
    }

    @Test
    void withoutTokenIsA401ProblemJsonWithRequestId() throws Exception {
        call(PROTECTED, null)
                .andExpect(status().isUnauthorized())
                .andExpect(
                        header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        call(PROTECTED, TestJwt.valid(List.of(), List.of())).andExpect(status().isOk());
    }

    @Test
    void invalidTokensAreAll401() throws Exception {
        for (String token :
                List.of(
                        "pas.un.jeton",
                        TestJwt.expired(),
                        TestJwt.wrongIssuer(),
                        TestJwt.signedByAnotherKey(),
                        TestJwt.hmacWithPublicKey())) {
            call(PROTECTED, token)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Test
    void missingPermissionIsA403ProblemJson() throws Exception {
        call(NEEDS_PERMISSION, TestJwt.valid(List.of("CUSTOMER"), List.of()))
                .andExpect(status().isForbidden())
                .andExpect(
                        header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void permissionClaimGrantsAccess() throws Exception {
        call(NEEDS_PERMISSION, TestJwt.valid(List.of("ADMIN"), List.of("TRANSFER_READ")))
                .andExpect(status().isOk());
    }

    @Test
    void roleClaimIsMappedToRoleAuthorities() throws Exception {
        call("/api/admin/_probe/admin-role", TestJwt.valid(List.of("ADMIN"), List.of()))
                .andExpect(status().isOk());
        call("/api/admin/_probe/admin-role", TestJwt.valid(List.of("CUSTOMER"), List.of()))
                .andExpect(status().isForbidden());
    }

    @Test
    void corsAllowsOnlyConfiguredOrigins() throws Exception {
        mvc.perform(
                        options("/api/countries")
                                .header("Origin", "https://app.example.test")
                                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("Access-Control-Allow-Origin", "https://app.example.test"));
        mvc.perform(
                        options("/api/countries")
                                .header("Origin", "https://evil.example")
                                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void responsesCarrySecurityHeadersAndNoSession() throws Exception {
        call("/api/countries", null)
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(
                        header().string(
                                        "Content-Security-Policy",
                                        "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().exists("Strict-Transport-Security"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
