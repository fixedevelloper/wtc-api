package com.wtc.identity.infrastructure;

import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.Role;
import com.wtc.identity.domain.User;
import com.wtc.identity.domain.UserStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcUserRepository implements UserRepository {

    private static final String SELECT =
            """
            SELECT u.id, u.public_id, u.email, u.password_hash, u.display_name, u.phone_e164, u.status,
                   u.mfa_enabled, (u.mfa_secret_encrypted IS NOT NULL) AS mfa_secret_present, u.mfa_last_used_step,
                   u.failed_login_count, u.locked_until, u.last_login_at, u.photo_storage_key,
                   COALESCE((SELECT array_agg(ur.role_code) FROM user_roles ur WHERE ur.user_id = u.id), '{}') AS roles
            FROM users u
            """;

    /** Colonnes autorisées pour le tri : jamais de nom de colonne issu du client. */
    private static final Map<String, String> SORTABLE =
            Map.of(
                    "displayName", "u.display_name",
                    "email", "lower(u.email)",
                    "createdAt", "u.created_at",
                    "lastLoginAt", "u.last_login_at");

    private final JdbcClient jdbc;

    JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jdbc.sql(SELECT + " WHERE lower(u.email) = lower(:email)")
                .param("email", email)
                .query(JdbcUserRepository::map)
                .optional();
    }

    @Override
    public Optional<User> findByPublicId(UUID publicId) {
        return jdbc.sql(SELECT + " WHERE u.public_id = :id")
                .param("id", publicId)
                .query(JdbcUserRepository::map)
                .optional();
    }

    @Override
    public Optional<User> findById(long id) {
        return jdbc.sql(SELECT + " WHERE u.id = :id")
                .param("id", id)
                .query(JdbcUserRepository::map)
                .optional();
    }

    @Override
    public boolean emailExists(String email) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE lower(email) = lower(:email))")
                .param("email", email)
                .query(Boolean.class)
                .single();
    }

    @Override
    public boolean phoneExists(String phone) {
        return phone != null
                && jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE phone_e164 = :phone)")
                        .param("phone", phone)
                        .query(Boolean.class)
                        .single();
    }

    @Override
    public long insert(NewUser user) {
        long id =
                jdbc.sql(
                                """
                                INSERT INTO users (email, password_hash, display_name, phone_e164, status, password_changed_at)
                                VALUES (:email, :hash, :name, :phone, 'ACTIVE', now())
                                RETURNING id
                                """)
                        .param("email", user.email())
                        .param("hash", user.passwordHash())
                        .param("name", user.displayName())
                        .param("phone", user.phone())
                        .query(Long.class)
                        .single();
        replaceRoles(id, user.roles());
        return id;
    }

    @Override
    public boolean anyWithRole(Role role) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_roles WHERE role_code = :role)")
                .param("role", role.name())
                .query(Boolean.class)
                .single();
    }

    @Override
    public User recordFailedLogin(
            long userId, int maxAttempts, Duration lockDuration, Instant now) {
        // Une seule instruction : le compteur ne se perd pas sous concurrence et le verrou est posé
        // au seuil exact.
        jdbc.sql(
                        """
                        UPDATE users SET
                            failed_login_count = CASE WHEN failed_login_count + 1 >= :max THEN 0 ELSE failed_login_count + 1 END,
                            locked_until = CASE WHEN failed_login_count + 1 >= :max THEN :lockUntil ELSE locked_until END
                        WHERE id = :id
                        """)
                .param("max", maxAttempts)
                .param("lockUntil", Timestamp.from(now.plus(lockDuration)))
                .param("id", userId)
                .update();
        return findById(userId).orElseThrow();
    }

    @Override
    public void recordSuccessfulLogin(long userId, Instant now) {
        jdbc.sql(
                        "UPDATE users SET failed_login_count = 0, locked_until = NULL,"
                                + " last_login_at = :now WHERE id = :id")
                .param("now", Timestamp.from(now))
                .param("id", userId)
                .update();
    }

    @Override
    public void clearLock(long userId) {
        jdbc.sql("UPDATE users SET failed_login_count = 0, locked_until = NULL WHERE id = :id")
                .param("id", userId)
                .update();
    }

    @Override
    public void updatePasswordHash(long userId, String hash, Instant now) {
        jdbc.sql(
                        "UPDATE users SET password_hash = :hash, password_changed_at = :now WHERE"
                                + " id = :id")
                .param("hash", hash)
                .param("now", Timestamp.from(now))
                .param("id", userId)
                .update();
    }

    @Override
    public void setDisplayName(long userId, String displayName) {
        jdbc.sql("UPDATE users SET display_name = :n WHERE id = :id")
                .param("n", displayName)
                .param("id", userId)
                .update();
    }

    @Override
    public void setPhotoKey(long userId, String key) {
        jdbc.sql("UPDATE users SET photo_storage_key = :k WHERE id = :id")
                .param("k", key)
                .param("id", userId)
                .update();
    }

    @Override
    public void updateContact(long userId, String displayName, String phone) {
        jdbc.sql("UPDATE users SET display_name = :n, phone_e164 = :p WHERE id = :id")
                .param("n", displayName)
                .param("p", phone)
                .param("id", userId)
                .update();
    }

    @Override
    public boolean phoneUsedByAnother(String phone, long userId) {
        return phone != null
                && jdbc.sql(
                                "SELECT EXISTS (SELECT 1 FROM users WHERE phone_e164 = :p AND id <>"
                                        + " :id)")
                        .param("p", phone)
                        .param("id", userId)
                        .query(Boolean.class)
                        .single();
    }

    @Override
    public void setStatus(long userId, UserStatus status) {
        jdbc.sql("UPDATE users SET status = :s WHERE id = :id")
                .param("s", status.name())
                .param("id", userId)
                .update();
    }

    @Override
    public void replaceRoles(long userId, Set<Role> roles) {
        jdbc.sql("DELETE FROM user_roles WHERE user_id = :id").param("id", userId).update();
        for (Role role : roles) {
            jdbc.sql("INSERT INTO user_roles (user_id, role_code) VALUES (:id, :role)")
                    .param("id", userId)
                    .param("role", role.name())
                    .update();
        }
    }

    @Override
    public Set<String> permissionsOf(Set<Role> roles) {
        if (roles.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(
                jdbc.sql(
                                "SELECT DISTINCT permission_code FROM role_permissions WHERE"
                                        + " role_code IN (:roles)")
                        .param("roles", roles.stream().map(Role::name).toList())
                        .query(String.class)
                        .list());
    }

    @Override
    public void savePendingMfaSecret(long userId, byte[] encryptedSecret) {
        jdbc.sql(
                        "UPDATE users SET mfa_secret_encrypted = :s, mfa_enabled = false,"
                                + " mfa_last_used_step = NULL WHERE id = :id")
                .param("s", encryptedSecret)
                .param("id", userId)
                .update();
    }

    @Override
    public Optional<byte[]> findMfaSecret(long userId) {
        return jdbc.sql(
                        "SELECT mfa_secret_encrypted FROM users WHERE id = :id AND"
                                + " mfa_secret_encrypted IS NOT NULL")
                .param("id", userId)
                .query(byte[].class)
                .optional();
    }

    @Override
    public void enableMfa(long userId) {
        jdbc.sql(
                        "UPDATE users SET mfa_enabled = true WHERE id = :id AND"
                                + " mfa_secret_encrypted IS NOT NULL")
                .param("id", userId)
                .update();
    }

    @Override
    public void clearMfa(long userId) {
        jdbc.sql(
                        "UPDATE users SET mfa_enabled = false, mfa_secret_encrypted = NULL,"
                                + " mfa_last_used_step = NULL WHERE id = :id")
                .param("id", userId)
                .update();
    }

    @Override
    public boolean claimMfaStep(long userId, long step) {
        return jdbc.sql(
                                "UPDATE users SET mfa_last_used_step = :step WHERE id = :id AND"
                                    + " (mfa_last_used_step IS NULL OR mfa_last_used_step < :step)")
                        .param("step", step)
                        .param("id", userId)
                        .update()
                == 1;
    }

    @Override
    public UserPage searchStaff(
            String search, int page, int size, String sortField, boolean ascending) {
        String column = SORTABLE.getOrDefault(sortField, "u.created_at");
        String where =
                """
                 WHERE EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id = u.id AND r.role_code <> 'CUSTOMER')
                   AND (CAST(:search AS text) IS NULL OR u.email ILIKE :pattern OR u.display_name ILIKE :pattern)
                """;
        String pattern =
                search == null
                        ? null
                        : "%"
                                + search.replace("\\", "\\\\")
                                        .replace("%", "\\%")
                                        .replace("_", "\\_")
                                + "%";
        List<User> content =
                jdbc.sql(
                                SELECT
                                        + where
                                        + " ORDER BY "
                                        + column
                                        + (ascending ? " ASC" : " DESC")
                                        + " NULLS LAST, u.id LIMIT :size OFFSET :offset")
                        .param("search", search)
                        .param("pattern", pattern)
                        .param("size", size)
                        .param("offset", (long) page * size)
                        .query(JdbcUserRepository::map)
                        .list();
        long total =
                jdbc.sql("SELECT count(*) FROM users u" + where)
                        .param("search", search)
                        .param("pattern", pattern)
                        .query(Long.class)
                        .single();
        return new UserPage(content, total);
    }

    @Override
    public long lockAndCountActiveSuperAdmins() {
        return jdbc.sql(
                        """
                        SELECT count(*) FROM (
                            SELECT u.id FROM users u JOIN user_roles r ON r.user_id = u.id
                            WHERE r.role_code = 'SUPER_ADMIN' AND u.status = 'ACTIVE'
                            ORDER BY u.id
                            FOR UPDATE OF u
                        ) locked
                        """)
                .query(Long.class)
                .single();
    }

    private static User map(ResultSet rs, int row) throws SQLException {
        String[] roleCodes = (String[]) rs.getArray("roles").getArray();
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String code : roleCodes) {
            roles.add(Role.valueOf(code));
        }
        Timestamp locked = rs.getTimestamp("locked_until");
        Timestamp last = rs.getTimestamp("last_login_at");
        long step = rs.getLong("mfa_last_used_step");
        Long lastStep = rs.wasNull() ? null : step;
        return new User(
                rs.getLong("id"),
                rs.getObject("public_id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                rs.getString("phone_e164"),
                UserStatus.valueOf(rs.getString("status")),
                roles,
                rs.getBoolean("mfa_enabled"),
                rs.getBoolean("mfa_secret_present"),
                lastStep,
                rs.getInt("failed_login_count"),
                locked == null ? null : locked.toInstant(),
                last == null ? null : last.toInstant(),
                rs.getString("photo_storage_key"));
    }
}
