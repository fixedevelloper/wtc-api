package com.wtc.identity.infrastructure;

import com.wtc.identity.application.port.PasswordHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id (paramètres recommandés par Spring Security 5.8 : 16 octets de sel, 32 de hachage, 1
 * thread, 16 Mio, 2 itérations) pour les nouveaux hachages. Les hachages bcrypt hérités de Symfony
 * ({@code $2y$}, {@code $2a$}, {@code $2b$}) restent vérifiables et sont remplacés à la première
 * connexion réussie.
 */
@Component
class Argon2PasswordHasher implements PasswordHasher {

    private final Argon2PasswordEncoder argon2 =
            Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final String dummyHash = argon2.encode("dummy-password-for-constant-time-checks");

    @Override
    public String hash(String rawPassword) {
        return argon2.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String storedHash) {
        if (storedHash == null || storedHash.isBlank()) {
            return false;
        }
        if (storedHash.startsWith("$argon2")) {
            return argon2.matches(rawPassword, storedHash);
        }
        if (storedHash.startsWith("$2")) {
            return bcrypt.matches(rawPassword, storedHash.replaceFirst("^\\$2y\\$", "\\$2a\\$"));
        }
        return false;
    }

    @Override
    public boolean needsRehash(String storedHash) {
        return storedHash == null || !storedHash.startsWith("$argon2id$");
    }

    @Override
    public void burnTime(String rawPassword) {
        argon2.matches(rawPassword == null ? "" : rawPassword, dummyHash);
    }
}
