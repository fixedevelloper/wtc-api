package com.wtc.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.support.AbstractIT;
import com.wtc.support.OpenApiContract;
import com.wtc.support.OpenApiContract.Operation;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Le contrat OpenAPI est la source de vérité : aucune route ne peut exister sans y figurer, et la
 * sécurité HTTP doit rester identique à ce qu'il déclare (routes publiques / authentifiées), y
 * compris pour les opérations pas encore implémentées.
 */
class ContractConformanceIT extends AbstractIT {

    private static final Logger LOG = LoggerFactory.getLogger(ContractConformanceIT.class);

    @Autowired RequestMappingHandlerMapping mappings;

    @Autowired MockMvc mvc;

    @Test
    void everyImplementedRouteIsDeclaredInTheContract() {
        Set<String> declared = OpenApiContract.signatures();
        Set<String> undocumented = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                for (RequestMethod method : methodsOf(info)) {
                    String signature = method.name() + " " + OpenApiContract.normalize(pattern);
                    if (!declared.contains(signature)) {
                        undocumented.add(signature);
                    }
                }
            }
        }
        assertThat(undocumented)
                .as("routes implémentées mais absentes de api/openapi.yaml")
                .isEmpty();
    }

    @Test
    void httpSecurityMatchesTheContractForEveryOperation() throws Exception {
        List<String> mismatches = new java.util.ArrayList<>();
        for (Operation op : OpenApiContract.operations()) {
            String concretePath = op.path().replaceAll("\\{[^}]+}", "1");
            MockHttpServletRequestBuilder request =
                    MockMvcRequestBuilders.request(HttpMethod.valueOf(op.method()), concretePath);
            int status = mvc.perform(request).andReturn().getResponse().getStatus();
            boolean openAtHttpLevel = op.publicAccess() || op.waceSignature();
            if (openAtHttpLevel && status == 401) {
                mismatches.add(op.method() + " " + op.path() + " devrait être ouverte (401 reçu)");
            }
            if (!openAtHttpLevel && status != 401) {
                mismatches.add(
                        op.method()
                                + " "
                                + op.path()
                                + " devrait exiger un jeton (reçu "
                                + status
                                + ")");
            }
        }
        assertThat(mismatches).as("écarts entre la sécurité HTTP et le contrat").isEmpty();
    }

    @Test
    void reportsImplementationProgress() {
        Set<String> implemented = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                for (RequestMethod method : methodsOf(info)) {
                    implemented.add(method.name() + " " + OpenApiContract.normalize(pattern));
                }
            }
        }
        long done = OpenApiContract.signatures().stream().filter(implemented::contains).count();
        LOG.info(
                "Contrat : {} opérations sur {} implémentées",
                done,
                OpenApiContract.signatures().size());
        assertThat(done).as("opérations du contrat déjà implémentées").isGreaterThanOrEqualTo(4);
    }

    private static Set<RequestMethod> methodsOf(RequestMappingInfo info) {
        return new TreeSet<>(info.getMethodsCondition().getMethods());
    }
}
