package com.wtc.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wtc.support.AbstractIT;
import com.wtc.support.TestJwt;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Toutes les erreurs sont des {@code application/problem+json} (RFC 9457) avec un code stable et
 * sans fuite technique.
 */
@TestPropertySource(properties = "wtc.test.probe=true")
@ExtendWith(OutputCaptureExtension.class)
class ProblemDetailsIT extends AbstractIT {

    @Autowired MockMvc mvc;

    private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(
                request.header("Authorization", "Bearer " + TestJwt.valid(List.of(), List.of())));
    }

    @Test
    void validationErrorsAre422WithFieldDetails() throws Exception {
        send(post("/api/_probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"amount\":-5}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(
                        header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field=='amount')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='name')]").exists());
    }

    @Test
    void invalidRequestParameterIs422WithTheParameterName() throws Exception {
        send(get("/api/_probe/param").param("limit", "0"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        send(post("/api/_probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{pas du json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void wrongMethodIs405() throws Exception {
        send(post("/api/_probe/authenticated"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedMediaTypeIs415() throws Exception {
        send(post("/api/_probe/validate").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unknownRouteIs404() throws Exception {
        send(get("/api/_probe/n-existe-pas"))
                .andExpect(status().isNotFound())
                .andExpect(
                        header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void domainExceptionKeepsItsCodeAndMessage() throws Exception {
        send(get("/api/_probe/domain-error"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value("Le transfert est déjà terminé."));
    }

    @Test
    void unexpectedErrorIs500WithoutLeakingInternals(CapturedOutput output) throws Exception {
        String body =
                send(get("/api/_probe/crash"))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body)
                .doesNotContain("hunter2")
                .doesNotContain("jdbc:")
                .doesNotContain("IllegalStateException")
                .doesNotContain("at com.wtc");
        assertThat(output.getAll())
                .as("l'erreur est journalisée côté serveur")
                .contains("Erreur inattendue");
    }
}
