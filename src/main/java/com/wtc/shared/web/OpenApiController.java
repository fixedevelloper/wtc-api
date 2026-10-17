package com.wtc.shared.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publie le contrat d'API embarqué (le même fichier que celui validé en CI et utilisé pour générer
 * les clients).
 */
@RestController
public class OpenApiController {

    private static final MediaType YAML =
            new MediaType("application", "yaml", StandardCharsets.UTF_8);

    @GetMapping(value = "/api/docs/openapi.yaml", produces = "application/yaml;charset=UTF-8")
    ResponseEntity<String> contract() throws IOException {
        String yaml =
                StreamUtils.copyToString(
                        new ClassPathResource("openapi/openapi.yaml").getInputStream(),
                        StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(YAML)
                .cacheControl(CacheControl.noCache())
                .body(yaml);
    }
}
