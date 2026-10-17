package com.wtc.reference.infrastructure;

import com.wtc.reference.application.port.ClientRequirementsRepository;
import com.wtc.reference.domain.ClientRequirement;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lit {@code platform_settings['client.requirements']} (tableau JSON) sans mapping JSON Hibernate.
 */
@Repository
class JdbcClientRequirementsRepository implements ClientRequirementsRepository {

    private static final String KEY = "client.requirements";
    private static final TypeReference<List<ClientRequirement>> TYPE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcClientRequirementsRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<ClientRequirement> findAll() {
        return jdbc.sql("SELECT value::text FROM platform_settings WHERE key = :key")
                .param("key", KEY)
                .query(String.class)
                .optional()
                .map(raw -> json.readValue(raw, TYPE))
                .orElse(List.of());
    }
}
