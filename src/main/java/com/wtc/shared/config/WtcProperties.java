package com.wtc.shared.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/** Configuration applicative (préfixe {@code wtc}). Aucune valeur secrète n'a de défaut. */
@Validated
@ConfigurationProperties("wtc")
public record WtcProperties(
        @DefaultValue Security security,
        @DefaultValue Cors cors,
        @DefaultValue Problem problem,
        @DefaultValue RateLimits rateLimits,
        @DefaultValue Bootstrap bootstrap,
        @DefaultValue Storage storage,
        @DefaultValue Payment payment,
        @DefaultValue Notification notification) {

    /**
     * @param jwt émission et vérification des jetons
     * @param encryptionKey clé AES-256 (32 octets en Base64) qui chiffre les secrets stockés
     *     (secret TOTP, jetons dans l'outbox)
     * @param login politique de verrouillage des comptes
     */
    public record Security(
            @DefaultValue Jwt jwt,
            String encryptionKey,
            @DefaultValue Login login,
            String serviceKey) {
        @Override
        public String toString() {
            return "Security[jwt=" + jwt + ", login=" + login + ", secrets=***]";
        }
    }

    /**
     * @param privateKeyLocation clé privée RSA (PKCS#8, PEM) de signature ; absente = cette
     *     instance ne fait que vérifier
     * @param publicKeyLocation clé publique RSA (PEM) de vérification ; déduite de la clé privée si
     *     absente
     * @param issuer émetteur attendu et émis ({@code iss})
     * @param accessTtl durée de vie d'un jeton d'accès
     * @param refreshTtl durée de vie d'un refresh token (chaque rotation la renouvelle)
     * @param refreshMaxAge durée maximale d'une session, quelles que soient les rotations
     */
    public record Jwt(
            Resource privateKeyLocation,
            Resource publicKeyLocation,
            @DefaultValue("wtc-api") String issuer,
            @DefaultValue("15m") Duration accessTtl,
            @DefaultValue("14d") Duration refreshTtl,
            @DefaultValue("90d") Duration refreshMaxAge) {}

    /**
     * @param maxFailedAttempts échecs consécutifs avant verrouillage @param lockDuration durée du
     *     verrouillage
     */
    public record Login(
            @DefaultValue("5") int maxFailedAttempts, @DefaultValue("15m") Duration lockDuration) {}

    /**
     * @param allowedOrigins origines autorisées (CORS) ; vide = aucune
     */
    public record Cors(@DefaultValue List<String> allowedOrigins) {}

    /**
     * @param baseUri préfixe des URI de type d'erreur RFC 9457
     */
    public record Problem(@DefaultValue("https://docs.wtc.example/problems/") String baseUri) {}

    /**
     * Notifications. Chaque canal a un fournisseur à choisir <b>explicitement</b> (sans valeur par
     * défaut, démarrage refusé sinon) : {@code smtp}/{@code waba}/{@code telegram} (réels), {@code
     * fake} (développement, tests) ou {@code disabled} (canal éteint : ses messages sont ignorés,
     * pas perdus en erreur).
     */
    public record Notification(
            @DefaultValue Email email,
            @DefaultValue Whatsapp whatsapp,
            @DefaultValue Telegram telegram,
            @DefaultValue Links links,
            @DefaultValue NotificationWorker worker) {}

    /** Courriel : le serveur SMTP se règle par {@code spring.mail.*}. */
    public record Email(String provider, @DefaultValue("WTC <no-reply@wtc.example>") String from) {}

    /**
     * WhatsApp Business (Cloud API de Meta). Les modèles de message doivent être approuvés dans la
     * console Meta.
     */
    public record Whatsapp(
            String provider,
            @DefaultValue("https://graph.facebook.com/v21.0") String apiBaseUrl,
            String phoneNumberId,
            String accessToken,
            @DefaultValue("fr") String languageCode) {
        @Override
        public String toString() {
            return "Whatsapp[provider="
                    + provider
                    + ", phoneNumberId="
                    + phoneNumberId
                    + ", accessToken=***]";
        }
    }

    /** Bot Telegram pour les alertes à l'équipe. */
    public record Telegram(
            String provider,
            @DefaultValue("https://api.telegram.org") String apiBaseUrl,
            String botToken,
            String adminChatId) {
        @Override
        public String toString() {
            return "Telegram[provider="
                    + provider
                    + ", adminChatId="
                    + adminChatId
                    + ", botToken=***]";
        }
    }

    /**
     * @param webBaseUrl adresse publique de l'application web, base des liens envoyés par courriel
     */
    public record Links(@DefaultValue("http://localhost:3000") String webBaseUrl) {}

    public record NotificationWorker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10s") Duration pollInterval,
            @DefaultValue("20") int batchSize,
            @DefaultValue("6") int maxAttempts,
            @DefaultValue("true") boolean opsWatcherEnabled,
            @DefaultValue("60s") Duration opsWatchInterval) {}

    /**
     * Fournisseur de paiement. {@code provider} est <b>obligatoire</b> et sans valeur par défaut :
     * {@code wace} (réel) ou {@code fake} (développement et tests, jamais en production). Un
     * démarrage sans choix explicite est refusé.
     */
    public record Payment(String provider, @DefaultValue Wace wace, @DefaultValue Worker worker) {}

    /**
     * Accès à l'API Wace (spécification v2.0.0). Les identifiants viennent exclusivement de
     * l'environnement.
     */
    public record Wace(
            String baseUrl,
            String email,
            String password,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("30s") Duration requestTimeout) {
        @Override
        public String toString() {
            return "Wace[baseUrl="
                    + baseUrl
                    + ", email="
                    + email
                    + ", password=***]"; // jamais le mot de passe
        }
    }

    /** Travailleurs d'arrière-plan (envoi des ordres, suivi des statuts). */
    public record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5s") Duration outboxPollInterval,
            @DefaultValue("30s") Duration statusPollInterval,
            @DefaultValue("20") int batchSize,
            @DefaultValue("5") int maxNotProcessedAttempts,
            @DefaultValue("8") int maxConfirmAttempts) {}

    /**
     * Stockage objet compatible S3 (pièces KYC, photos). Le bucket est privé : on n'y accède que
     * par des liens signés de courte durée.
     *
     * @param endpoint URL du service (obligatoire), par exemple http://minio:9000
     * @param publicEndpoint URL utilisée dans les liens signés remis aux clients, si elle diffère
     *     de {@code endpoint}
     * @param autoCreateBucket crée le bucket s'il manque (développement et tests ; en production le
     *     bucket est provisionné)
     */
    public record Storage(
            String endpoint,
            String publicEndpoint,
            @DefaultValue("us-east-1") String region,
            @DefaultValue("wtc-private") String bucket,
            String accessKey,
            String secretKey,
            @DefaultValue("true") boolean pathStyle,
            @DefaultValue("false") boolean autoCreateBucket) {
        @Override
        public String toString() {
            return "Storage[endpoint="
                    + endpoint
                    + ", bucket="
                    + bucket
                    + ", secretKey=***]"; // jamais la clé secrète
        }
    }

    /**
     * Premier super-administrateur d'un environnement neuf (voir docs/runbook.md). Sans effet si un
     * super-administrateur existe déjà : on peut laisser ces variables en place sans risque, mais
     * il vaut mieux les retirer ensuite.
     */
    public record Bootstrap(String superAdminEmail, String superAdminPassword) {
        @Override
        public String toString() {
            return "Bootstrap[superAdminEmail="
                    + superAdminEmail
                    + ", superAdminPassword=***]"; // jamais le mot de passe
        }
    }

    /**
     * Plafonds de requêtes (par fenêtre glissante) contre l'énumération et le bourrage
     * d'identifiants.
     */
    public record RateLimits(
            @DefaultValue("5") int loginPerEmailPerMinute,
            @DefaultValue("30") int loginPerIpPerMinute,
            @DefaultValue("10") int registerPerIpPerHour,
            @DefaultValue("3") int passwordResetPerEmailPerHour,
            @DefaultValue("20") int passwordResetPerIpPerHour,
            @DefaultValue("60") int refreshPerIpPerMinute,
            @DefaultValue("5") int mfaAttemptsPerChallenge,
            @DefaultValue("10") int sensitiveActionsPerUserPerHour) {}
}
