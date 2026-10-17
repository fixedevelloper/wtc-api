package com.wtc.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PemKeysTest {

    @Test
    void readsAnRsaPublicKeyInPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey expected = (RSAPublicKey) generator.generateKeyPair().getPublic();
        String pem =
                "-----BEGIN PUBLIC KEY-----\n"
                        + Base64.getMimeEncoder(64, "\n".getBytes())
                                .encodeToString(expected.getEncoded())
                        + "\n-----END PUBLIC KEY-----\n";

        RSAPublicKey parsed = PemKeys.parseRsaPublicKey(pem);

        assertThat(parsed.getModulus()).isEqualTo(expected.getModulus());
        assertThat(parsed.getPublicExponent()).isEqualTo(expected.getPublicExponent());
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> PemKeys.parseRsaPublicKey("pas une clé"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalide");
    }
}
