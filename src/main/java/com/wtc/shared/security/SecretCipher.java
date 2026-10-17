package com.wtc.shared.security;

import com.wtc.shared.config.WtcProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Chiffrement applicatif AES-256-GCM des secrets stockés (secret TOTP, jetons placés dans l'outbox,
 * numéros de pièce…). Format : {@code IV (12 octets) || texte chiffré || étiquette GCM}. Le
 * contexte associé ({@code aad}) lie le chiffré à son usage : un secret copié dans une autre
 * colonne ou chez un autre utilisateur ne se déchiffre plus.
 */
@Component
public final class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(WtcProperties properties) {
        String encoded = properties.security().encryptionKey();
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(
                    "wtc.security.encryption-key est obligatoire (32 octets en Base64, ex. :"
                            + " openssl rand -base64 32)");
        }
        byte[] raw = Base64.getDecoder().decode(encoded.trim());
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "wtc.security.encryption-key doit faire exactement 32 octets (AES-256)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] encrypt(String plaintext, String aad) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Chiffrement impossible", e);
        }
    }

    public String decrypt(byte[] payload, String aad) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException(
                    "Déchiffrement impossible (clé ou contexte incorrect)", e);
        }
    }
}
