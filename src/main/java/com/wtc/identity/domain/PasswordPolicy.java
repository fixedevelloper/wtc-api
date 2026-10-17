package com.wtc.identity.domain;

import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Politique de mot de passe (NIST SP 800-63B) : la longueur prime sur la complexité. Minimum 10
 * caractères, maximum 128, refus des mots de passe très courants, de l'adresse e-mail et de sa
 * partie locale, des répétitions triviales.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 128;

    private static final Set<String> COMMON =
            Set.of(
                    "password12",
                    "password123",
                    "password1234",
                    "password12345",
                    "passw0rd123",
                    "motdepasse",
                    "motdepasse1",
                    "motdepasse12",
                    "motdepasse123",
                    "azertyuiop",
                    "azerty12345",
                    "azerty123456",
                    "qwertyuiop",
                    "qwerty12345",
                    "qwerty123456",
                    "1234567890",
                    "12345678901",
                    "123456789012",
                    "0123456789",
                    "0987654321",
                    "1q2w3e4r5t",
                    "iloveyou12",
                    "iloveyou123",
                    "welcome123",
                    "welcome1234",
                    "administrator",
                    "changeme123",
                    "letmein1234",
                    "abcdefghij",
                    "abcd123456",
                    "123456abcd",
                    "superman123",
                    "football123",
                    "monkey12345",
                    "dragon12345",
                    "wetransfer",
                    "wetransfercash",
                    "wtc1234567",
                    "wtc123456789");

    private PasswordPolicy() {}

    /**
     * @throws DomainException {@code WEAK_PASSWORD} avec le détail des règles non respectées
     */
    public static void requireStrong(String password, String email, String field) {
        List<FieldViolation> violations = new ArrayList<>();
        if (password == null || password.length() < MIN_LENGTH) {
            violations.add(
                    new FieldViolation(
                            field, "TooShort", "Au moins " + MIN_LENGTH + " caractères."));
        } else if (password.length() > MAX_LENGTH) {
            violations.add(
                    new FieldViolation(field, "TooLong", "Au plus " + MAX_LENGTH + " caractères."));
        } else {
            String lower = password.toLowerCase(Locale.ROOT);
            if (COMMON.contains(lower)) {
                violations.add(
                        new FieldViolation(
                                field, "TooCommon", "Ce mot de passe est trop courant."));
            }
            if (email != null && !email.isBlank()) {
                String address = email.toLowerCase(Locale.ROOT);
                String local =
                        address.contains("@")
                                ? address.substring(0, address.indexOf('@'))
                                : address;
                if (lower.equals(address) || (local.length() >= 4 && lower.contains(local))) {
                    violations.add(
                            new FieldViolation(
                                    field,
                                    "ContainsEmail",
                                    "Ne doit pas contenir votre adresse e-mail."));
                }
            }
            if (lower.chars().distinct().count() <= 2) {
                violations.add(new FieldViolation(field, "TooRepetitive", "Trop de répétitions."));
            }
        }
        if (!violations.isEmpty()) {
            throw new DomainException(
                    ErrorCode.WEAK_PASSWORD,
                    "Le mot de passe ne respecte pas la politique de sécurité.",
                    violations);
        }
    }
}
