package com.wtc.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wtc.support.TestProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private final SecretCipher cipher =
            new SecretCipher(TestProperties.withEncryptionKey(TestProperties.KEY));

    @Test
    void roundTripsAndNeverRepeatsACiphertext() {
        byte[] first = cipher.encrypt("JBSWY3DPEHPK3PXP", "user-mfa:1");
        byte[] second = cipher.encrypt("JBSWY3DPEHPK3PXP", "user-mfa:1");

        assertThat(cipher.decrypt(first, "user-mfa:1")).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(first)
                .as("IV aléatoire : deux chiffrements du même texte diffèrent")
                .isNotEqualTo(second);
        assertThat(new String(first, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("JBSWY3DPEHPK3PXP");
    }

    @Test
    void aSecretCopiedToAnotherContextCannotBeDecrypted() {
        byte[] encrypted = cipher.encrypt("secret", "user-mfa:1");
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "user-mfa:2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperingIsDetected() {
        byte[] encrypted = cipher.encrypt("secret", "ctx");
        encrypted[encrypted.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "ctx"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(new byte[] {1, 2, 3}, "ctx"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherKeyCannotDecrypt() {
        SecretCipher other =
                new SecretCipher(
                        TestProperties.withEncryptionKey(
                                Base64.getEncoder().encodeToString(new byte[32])));
        byte[] encrypted = cipher.encrypt("secret", "ctx");
        assertThatThrownBy(() -> other.decrypt(encrypted, "ctx"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithoutAProperKey() {
        assertThatThrownBy(() -> new SecretCipher(TestProperties.withEncryptionKey(null)))
                .hasMessageContaining("obligatoire");
        assertThatThrownBy(() -> new SecretCipher(TestProperties.withEncryptionKey("")))
                .hasMessageContaining("obligatoire");
        assertThatThrownBy(
                        () ->
                                new SecretCipher(
                                        TestProperties.withEncryptionKey(
                                                Base64.getEncoder().encodeToString(new byte[16]))))
                .hasMessageContaining("32 octets");
    }
}
