package com.wtc.support;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Petit client JSON au-dessus de MockMvc, pour que les tests lisent comme des scénarios. */
public final class Api {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;

    public Api(MockMvc mvc) {
        this.mvc = mvc;
    }

    /** Réponse HTTP décodée. */
    public record Reply(MvcResult raw) {

        public byte[] rawBody() {
            return raw.getResponse().getContentAsByteArray();
        }

        public int status() {
            return raw.getResponse().getStatus();
        }

        public String header(String name) {
            return raw.getResponse().getHeader(name);
        }

        public String body() {
            try {
                return raw.getResponse().getContentAsString();
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Écarts entre le corps reçu et le schéma OpenAPI de l'opération (vide = conforme). */
        public java.util.Set<?> contractViolations(String operationId, int status) {
            try {
                return OpenApiContract.validateResponse(operationId, status, body());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

        public JsonNode json() {
            return body().isEmpty() ? JSON.createObjectNode() : JSON.readTree(body());
        }

        public String text(String field) {
            JsonNode node = json().get(field);
            return node == null || node.isNull() ? null : node.asString();
        }

        /** Champ de la première violation d'une réponse 422 (ou null). */
        public String field(String expected) {
            var errors = json().get("errors");
            if (errors == null || errors.isEmpty()) {
                return status() == 422
                        ? "(aucune violation détaillée)"
                        : "(statut " + status() + ")";
            }
            for (var error : errors) {
                if (expected.equals(error.get("field").asString())) {
                    return expected;
                }
            }
            return errors.get(0).get("field").asString();
        }

        /** Code applicatif d'une réponse {@code problem+json}. */
        public String code() {
            return text("code");
        }
    }

    public Reply post(String path, Object body, String token) {
        return send(MockMvcRequestBuilders.post(path), body, token);
    }

    public Reply post(String path, Object body) {
        return send(MockMvcRequestBuilders.post(path), body, null);
    }

    public Reply put(String path, Object body, String token) {
        return send(MockMvcRequestBuilders.put(path), body, token);
    }

    public Reply patch(String path, Object body, String token) {
        return send(MockMvcRequestBuilders.patch(path), body, token);
    }

    public Reply delete(String path, Object body, String token) {
        return send(MockMvcRequestBuilders.delete(path), body, token);
    }

    /** Envoi multipart : champs texte puis un fichier nommé {@code file}. */
    public Reply upload(
            String method, String path, Map<String, ?> fields, byte[] file, String token) {
        try {
            org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder
                    request =
                            MockMvcRequestBuilders.multipart(
                                    org.springframework.http.HttpMethod.valueOf(method), path);
            fields.forEach((k, v) -> request.param(k, String.valueOf(v)));
            request.file(
                    new org.springframework.mock.web.MockMultipartFile(
                            "file", "document.bin", "application/octet-stream", file));
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            return new Reply(mvc.perform(request).andReturn());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Reply get(String path, String token) {
        return send(MockMvcRequestBuilders.get(path), null, token);
    }

    public static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private Reply send(MockHttpServletRequestBuilder request, Object body, String token) {
        try {
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body));
            }
            return new Reply(mvc.perform(request).andReturn());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
