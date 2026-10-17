package com.wtc.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wtc.support.AbstractIT;
import com.wtc.support.OpenApiContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Première tranche verticale de bout en bout : HTTP → cas d'usage → JPA → PostgreSQL, conforme au
 * contrat.
 */
@Transactional
class ReferenceApiIT extends AbstractIT {

    @Autowired MockMvc mvc;

    @Autowired JdbcClient jdbc;

    @BeforeEach
    void data() {
        jdbc.sql("INSERT INTO pricing_zones (code, name) VALUES ('Z-TEST', 'Zone de test')")
                .update();
        jdbc.sql(
                        """
                        INSERT INTO countries (code, iso3, name, currency_code, calling_code, pricing_zone_id, can_send, can_receive, enabled)
                        VALUES ('CG', 'COG', 'Congo', 'XAF', '+242', (SELECT id FROM pricing_zones WHERE code = 'Z-TEST'), true, true, true),
                               ('FR', 'FRA', 'France', 'EUR', '+33',  (SELECT id FROM pricing_zones WHERE code = 'Z-TEST'), true, true, true),
                               ('XX', 'XXX', 'Pays désactivé', 'XAF', null, null, false, false, false)
                        """)
                .update();
        jdbc.sql(
                        "INSERT INTO cities (country_code, name) VALUES ('CG', 'Pointe-Noire'),"
                                + " ('CG', 'Brazzaville'), ('FR', 'Paris'), ('XX', 'Nulle part')")
                .update();
    }

    @Test
    void listsEnabledCountriesSortedByNameAndMatchesTheContract() throws Exception {
        MvcResult result =
                mvc.perform(get("/api/countries"))
                        .andExpect(status().isOk())
                        .andExpect(
                                header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                        .andExpect(header().string("Cache-Control", "max-age=300, public"))
                        .andExpect(jsonPath("$.length()").value(2))
                        .andExpect(jsonPath("$[0].code").value("CG"))
                        .andExpect(jsonPath("$[0].currencyCode").value("XAF"))
                        .andExpect(jsonPath("$[0].canSend").value(true))
                        .andExpect(jsonPath("$[1].code").value("FR"))
                        .andExpect(
                                jsonPath("$[0].flagUrl")
                                        .doesNotExist()) // valeur nulle : champ omis, comme le
                        // contrat
                        .andReturn();

        assertThat(
                        OpenApiContract.validateResponse(
                                "listCountries", 200, result.getResponse().getContentAsString()))
                .isEmpty();
    }

    @Test
    void listsCitiesOfACountryRegardlessOfCase() throws Exception {
        MvcResult result =
                mvc.perform(get("/api/countries/cg/cities"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(2))
                        .andExpect(jsonPath("$[0].name").value("Brazzaville"))
                        .andExpect(jsonPath("$[1].name").value("Pointe-Noire"))
                        .andReturn();

        assertThat(
                        OpenApiContract.validateResponse(
                                "listCities", 200, result.getResponse().getContentAsString()))
                .isEmpty();
    }

    @Test
    void unknownOrDisabledCountryIsAProblemJson404() throws Exception {
        for (String code : new String[] {"ZZ", "XX"}) {
            MvcResult result =
                    mvc.perform(get("/api/countries/" + code + "/cities"))
                            .andExpect(status().isNotFound())
                            .andExpect(
                                    header().string(
                                                    "Content-Type",
                                                    MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                            .andExpect(jsonPath("$.code").value("COUNTRY_NOT_FOUND"))
                            .andExpect(jsonPath("$.status").value(404))
                            .andExpect(
                                    jsonPath("$.type")
                                            .value(
                                                    "https://docs.wtc.example/problems/country-not-found"))
                            .andExpect(jsonPath("$.requestId").isNotEmpty())
                            .andReturn();
            assertThat(
                            OpenApiContract.validateResponse(
                                    "listCities", 404, result.getResponse().getContentAsString()))
                    .isEmpty();
        }
    }

    @Test
    void exposesClientRequirementsFromPlatformSettings() throws Exception {
        MvcResult result =
                mvc.perform(get("/api/meta/client-requirements"))
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$[?(@.platform=='ANDROID')].minimumVersion")
                                        .value("1.0.0"))
                        .andExpect(jsonPath("$[?(@.platform=='WEB')]").exists())
                        .andReturn();

        assertThat(
                        OpenApiContract.validateResponse(
                                "getClientRequirements",
                                200,
                                result.getResponse().getContentAsString()))
                .isEmpty();
    }

    @Test
    void servesTheEmbeddedContract() throws Exception {
        mvc.perform(get("/api/docs/openapi.yaml"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/yaml;charset=UTF-8"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .string(org.hamcrest.Matchers.startsWith("# Source de vérité")));
    }

    @Test
    void everyResponseCarriesARequestIdAndHonoursAValidIncomingOne() throws Exception {
        mvc.perform(get("/api/countries")).andExpect(header().exists("X-Request-Id"));
        mvc.perform(get("/api/countries").header("X-Request-Id", "front-req-98765"))
                .andExpect(header().string("X-Request-Id", "front-req-98765"));
    }
}
