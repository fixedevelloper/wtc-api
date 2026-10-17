package com.wtc.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class Argon2PasswordHasherTest {

    private final Argon2PasswordHasher hasher = new Argon2PasswordHasher();

    @Test
    void hashesWithArgon2idAndVerifies() {
        String hash = hasher.hash("cheval agrafe pile bleu");

        assertThat(hash).startsWith("$argon2id$");
        assertThat(hasher.matches("cheval agrafe pile bleu", hash)).isTrue();
        assertThat(hasher.matches("autre", hash)).isFalse();
        assertThat(hasher.hash("cheval agrafe pile bleu")).as("sel aléatoire").isNotEqualTo(hash);
        assertThat(hasher.needsRehash(hash)).isFalse();
    }

    @Test
    void acceptsSymfonyBcryptHashesAndAsksForAnUpgrade() {
        String bcrypt = new BCryptPasswordEncoder().encode("ancien secret");
        String symfony = bcrypt.replaceFirst("^\\$2a\\$", "\\$2y\\$");

        assertThat(hasher.matches("ancien secret", symfony)).isTrue();
        assertThat(hasher.matches("autre", symfony)).isFalse();
        assertThat(hasher.needsRehash(symfony)).isTrue();
    }

    @Test
    void neverAcceptsEmptyOrUnknownFormats() {
        assertThat(hasher.matches("x", null)).isFalse();
        assertThat(hasher.matches("x", "")).isFalse();
        assertThat(hasher.matches("x", "x")).isFalse();
        assertThat(hasher.matches("plain", "plain"))
                .as("jamais de mot de passe en clair")
                .isFalse();
        assertThat(hasher.needsRehash(null)).isTrue();
    }

    @Test
    void burnTimeNeverFails() {
        assertThatCode(() -> hasher.burnTime("n'importe quoi")).doesNotThrowAnyException();
        assertThatCode(() -> hasher.burnTime(null)).doesNotThrowAnyException();
    }
}
