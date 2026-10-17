package com.wtc.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.shared.security.SecretCipher;
import com.wtc.support.TestProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NotificationTemplatesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SecretCipher cipher =
            new SecretCipher(TestProperties.withEncryptionKey(TestProperties.KEY));
    private final NotificationTemplates templates =
            new NotificationTemplates(cipher, TestProperties.withEncryptionKey(TestProperties.KEY));

    @Test
    void passwordResetLinkCarriesTheDecryptedTokenEncodedInTheUrl() {
        String sealed =
                Base64.getEncoder()
                        .encodeToString(
                                cipher.encrypt("a+b/c=", NotificationTemplates.TOKEN_CONTEXT));
        var p =
                JSON.createObjectNode()
                        .put("displayName", "Awa <script>")
                        .put("expiresAt", "demain")
                        .put("tokenEncrypted", sealed);
        Rendered r = templates.render("password-reset", Channel.EMAIL, p).orElseThrow();
        assertThat(r.text())
                .contains("https://app.wtc.example/auth/reset-password?token=a%2Bb%2Fc%3D");
        assertThat(r.html()).contains("&lt;script&gt;").doesNotContain("<script>");
        assertThat(r.toString()).doesNotContain("a%2Bb");
        assertThat(templates.render("password-reset", Channel.WHATSAPP, p)).isEmpty();
    }

    @Test
    void whatsappUsesTheApprovedTemplateWithOrderedParameters() {
        var p =
                JSON.createObjectNode()
                        .put("displayName", "Awa")
                        .put("reference", "WTC-2026-000001")
                        .put("destinationAmount", "9650")
                        .put("destinationCurrency", "XAF")
                        .put("beneficiaryName", "Paul");
        Rendered r = templates.render("transfer-completed", Channel.WHATSAPP, p).orElseThrow();
        assertThat(r.whatsappTemplate()).isEqualTo("wtc_transfer_completed");
        assertThat(r.whatsappParams())
                .containsExactly("Awa", "WTC-2026-000001", "9650 XAF", "Paul");
    }

    @Test
    void unknownTemplatesAndMismatchedChannelsProduceNothing() {
        var p = JSON.createObjectNode();
        assertThat(templates.render("nope", Channel.EMAIL, p)).isEmpty();
        assertThat(templates.render("ops-alert", Channel.EMAIL, p)).isEmpty();
        assertThat(templates.render("ops-alert", Channel.TELEGRAM, p)).isPresent();
        assertThat(templates.render("kyc-approved", Channel.WHATSAPP, p)).isEmpty();
    }
}
