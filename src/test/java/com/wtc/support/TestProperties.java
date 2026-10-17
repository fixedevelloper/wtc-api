package com.wtc.support;

import com.wtc.shared.config.WtcProperties;
import java.time.Duration;
import java.util.List;

/** Fabrique de {@link WtcProperties} pour les tests unitaires (valeurs par défaut du produit). */
public final class TestProperties {

    public static final String TEST_SERVICE_KEY = "test-service-key-0123456789abcdef";
    public static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private TestProperties() {}

    public static WtcProperties withEncryptionKey(String key) {
        return new WtcProperties(
                new WtcProperties.Security(
                        new WtcProperties.Jwt(
                                null,
                                null,
                                "wtc-test",
                                Duration.ofMinutes(15),
                                Duration.ofDays(14),
                                Duration.ofDays(90)),
                        key,
                        new WtcProperties.Login(5, Duration.ofMinutes(15)),
                        TEST_SERVICE_KEY),
                new WtcProperties.Cors(List.of()),
                new WtcProperties.Problem("https://docs.wtc.example/problems/"),
                new WtcProperties.RateLimits(5, 30, 10, 3, 20, 60, 5, 10),
                new WtcProperties.Bootstrap(null, null),
                new WtcProperties.Storage(
                        null, null, "us-east-1", "wtc-private", null, null, true, false),
                new WtcProperties.Payment(
                        "fake",
                        new WtcProperties.Wace(
                                null, null, null, Duration.ofSeconds(5), Duration.ofSeconds(30)),
                        new WtcProperties.Worker(
                                false, Duration.ofSeconds(5), Duration.ofSeconds(30), 20, 5, 8)),
                new WtcProperties.Notification(
                        new WtcProperties.Email("fake", "WTC <no-reply@wtc.example>"),
                        new WtcProperties.Whatsapp(
                                "fake", "https://graph.facebook.com/v21.0", null, null, "fr"),
                        new WtcProperties.Telegram(
                                "fake", "https://api.telegram.org", null, "-1001"),
                        new WtcProperties.Links("https://app.wtc.example"),
                        new WtcProperties.NotificationWorker(
                                false,
                                Duration.ofSeconds(10),
                                20,
                                6,
                                false,
                                Duration.ofSeconds(60))));
    }
}
