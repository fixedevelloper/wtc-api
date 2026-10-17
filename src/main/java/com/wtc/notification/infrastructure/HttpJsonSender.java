package com.wtc.notification.infrastructure;

import com.wtc.notification.application.SendResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Envoi JSON par HTTPS partagé par les canaux WhatsApp et Telegram, avec classement uniforme des
 * échecs.
 */
final class HttpJsonSender {

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * 2xx : envoyé ; 429 et 5xx : réessayer ; 408 : réessayer ; autre 4xx : définitif (jeton,
     * modèle, destinataire invalides).
     */
    SendResult post(String url, String jsonBody, Map<String, String> headers) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json");
        headers.forEach(request::header);
        try {
            HttpResponse<String> response =
                    client.send(
                            request.POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    jsonBody, StandardCharsets.UTF_8))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 == 2) {
                return SendResult.sent();
            }
            if (status == 429 || status == 408 || status >= 500) {
                return SendResult.retry("HTTP " + status);
            }
            return SendResult.permanent("HTTP " + status + " : " + abbreviate(response.body()));
        } catch (HttpConnectTimeoutException e) {
            return SendResult.retry("Connexion impossible");
        } catch (IOException e) {
            return SendResult.retry("Erreur réseau : " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.retry("Envoi interrompu");
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ");
        return flat.substring(0, Math.min(flat.length(), 200));
    }
}
