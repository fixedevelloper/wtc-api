package com.wtc.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TotpTest {

    /**
     * Secret ASCII « 12345678901234567890 » : vecteurs de test de la RFC 6238, annexe B (SHA-1, 8
     * chiffres tronqués à 6).
     */
    private static final byte[] RFC_SECRET =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest(name = "t={0} -> {1}")
    @CsvSource({
        "59,287082",
        "1111111109,081804",
        "1111111111,050471",
        "1234567890,005924",
        "2000000000,279037",
        "20000000000,353130"
    })
    void matchesTheRfc6238TestVectors(long epochSeconds, String expected) {
        assertThat(Totp.codeAt(RFC_SECRET, Totp.stepAt(epochSeconds))).isEqualTo(expected);
    }

    @Test
    void acceptsTheCurrentAndAdjacentSteps() {
        long now = 1_700_000_000L;
        long step = Totp.stepAt(now);
        for (long s = step - 1; s <= step + 1; s++) {
            assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, s), now, null)).hasValue(s);
        }
    }

    @Test
    void rejectsCodesOutsideTheWindow() {
        long now = 1_700_000_000L;
        long step = Totp.stepAt(now);
        assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, step - 2), now, null)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, step + 2), now, null)).isEmpty();
    }

    @Test
    void aCodeCanOnlyBeUsedOnce() {
        long now = 1_700_000_000L;
        long step = Totp.stepAt(now);
        String code = Totp.codeAt(RFC_SECRET, step);

        OptionalLong first = Totp.verify(RFC_SECRET, code, now, null);
        assertThat(first).hasValue(step);
        assertThat(Totp.verify(RFC_SECRET, code, now, first.getAsLong()))
                .as("rejeu du même code")
                .isEmpty();
        assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, step - 1), now, step))
                .as("code plus ancien")
                .isEmpty();
    }

    @ParameterizedTest
    @CsvSource(
            value = {"abcdef", "12345", "1234567", "'12 456'", "000000"},
            quoteCharacter = '\'')
    void rejectsMalformedOrWrongCodes(String code) {
        assertThat(Totp.verify(RFC_SECRET, code, 1_700_000_000L, null)).isEmpty();
    }

    @Test
    void base32RoundTripsAndMatchesTheRfc4648Vectors() {
        assertThat(Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("MZXW6YTBOI");
        assertThat(Base32.decode("MZXW6YTBOI"))
                .isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
        byte[] secret = Totp.newSecret();
        assertThat(secret).hasSize(20);
        assertThat(Totp.fromBase32(Totp.toBase32(secret))).isEqualTo(secret);
    }

    @Test
    void buildsAnOtpauthUriForAuthenticatorApps() {
        String uri = Totp.otpauthUri("WTC", "agent@example.com", RFC_SECRET);
        assertThat(uri)
                .startsWith(
                        "otpauth://totp/WTC:agent%40example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
                .contains("&issuer=WTC")
                .contains("&digits=6")
                .contains("&period=30");
    }
}
