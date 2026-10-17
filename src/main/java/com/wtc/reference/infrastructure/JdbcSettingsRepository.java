package com.wtc.reference.infrastructure;

import com.wtc.reference.application.SettingView;
import com.wtc.reference.application.port.SettingsRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSettingsRepository implements SettingsRepository {

    private static final RowMapper<SettingView> MAPPER =
            (rs, n) ->
                    new SettingView(
                            rs.getString("key"),
                            rs.getString("value"),
                            rs.getTimestamp("updated_at").toInstant());

    private final JdbcClient jdbc;

    JdbcSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SettingView> list() {
        return jdbc.sql(
                        "SELECT key, value::text AS value, updated_at FROM platform_settings ORDER"
                                + " BY key")
                .query(MAPPER)
                .list();
    }

    @Override
    public Optional<SettingView> find(String key) {
        return jdbc.sql(
                        "SELECT key, value::text AS value, updated_at FROM platform_settings WHERE"
                                + " key = :k")
                .param("k", key)
                .query(MAPPER)
                .optional();
    }

    @Override
    public void update(String key, String valueJson, UUID updatedBy) {
        jdbc.sql(
                        "UPDATE platform_settings SET value = CAST(:v AS jsonb), updated_at ="
                                + " now(), updated_by = (SELECT id FROM users WHERE public_id = :u)"
                                + " WHERE key = :k")
                .param("v", valueJson)
                .param("u", updatedBy)
                .param("k", key)
                .update();
    }
}
