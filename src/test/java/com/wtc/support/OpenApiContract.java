package com.wtc.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;

/** Accès au contrat OpenAPI embarqué, pour vérifier que l'implémentation lui est conforme. */
public final class OpenApiContract {

    /** Une opération du contrat. */
    public record Operation(
            String method,
            String path,
            String operationId,
            boolean publicAccess,
            boolean waceSignature,
            JsonNode node) {}

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode ROOT = load();

    private OpenApiContract() {}

    private static JsonNode load() {
        try (InputStream in = new ClassPathResource("openapi/openapi.yaml").getInputStream()) {
            return YAML.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Contrat OpenAPI introuvable dans le classpath", e);
        }
    }

    /**
     * Codes d'erreur applicatifs documentés (extension {@code x-known-codes} du schéma Problem).
     */
    public static List<String> knownErrorCodes() {
        List<String> codes = new ArrayList<>();
        ROOT.at("/components/schemas/Problem/properties/code/x-known-codes")
                .forEach(node -> codes.add(node.asText()));
        return codes;
    }

    /** Toutes les opérations, avec le préfixe de serveur {@code /api} ajouté aux chemins. */
    public static List<Operation> operations() {
        String prefix = ROOT.at("/servers/0/url").asText();
        List<Operation> operations = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> paths = ROOT.get("paths").fields();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> path = paths.next();
            Iterator<Map.Entry<String, JsonNode>> methods = path.getValue().fields();
            while (methods.hasNext()) {
                Map.Entry<String, JsonNode> method = methods.next();
                JsonNode op = method.getValue();
                JsonNode security = op.get("security");
                boolean publicAccess = security != null && security.isArray() && security.isEmpty();
                boolean wace =
                        security != null
                                && security.isArray()
                                && !security.isEmpty()
                                && security.get(0).has("waceSignature");
                operations.add(
                        new Operation(
                                method.getKey().toUpperCase(Locale.ROOT),
                                prefix + path.getKey(),
                                op.get("operationId").asText(),
                                publicAccess,
                                wace,
                                op));
            }
        }
        return operations;
    }

    /**
     * Chemin normalisé pour comparer {@code /api/countries/{countryCode}/cities} et {@code
     * /api/countries/{code}/cities}.
     */
    public static String normalize(String path) {
        return path.replaceAll("\\{[^}]+}", "{}");
    }

    public static Set<String> signatures() {
        return operations().stream()
                .map(o -> o.method() + " " + normalize(o.path()))
                .collect(Collectors.toSet());
    }

    /**
     * Valide un corps de réponse contre le schéma déclaré dans le contrat ; retourne les écarts
     * (vide = conforme).
     */
    public static Set<ValidationMessage> validateResponse(
            String operationId, int status, String body) throws IOException {
        Operation operation =
                operations().stream()
                        .filter(o -> o.operationId().equals(operationId))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Opération inconnue du contrat : " + operationId));
        JsonNode response = operation.node().at("/responses/" + status);
        if (response.has("$ref")) { // #/components/responses/NotFound
            response = ROOT.at(response.get("$ref").asText().substring(1));
        }
        JsonNode content = response.path("content");
        if (content.isMissingNode() || !content.fields().hasNext()) {
            throw new IllegalArgumentException(
                    "Aucun corps déclaré pour " + operationId + " " + status);
        }
        JsonNode schema = content.fields().next().getValue().get("schema");
        // Le schéma est enveloppé avec `components` pour que les $ref « #/components/schemas/... »
        // se résolvent.
        ObjectNode wrapper = JSON.createObjectNode();
        wrapper.setAll((ObjectNode) schema.deepCopy());
        wrapper.set("components", ROOT.get("components").deepCopy());
        JsonSchema compiled =
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(wrapper);
        return compiled.validate(JSON.readTree(body));
    }
}
