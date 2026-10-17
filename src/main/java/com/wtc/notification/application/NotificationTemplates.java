package com.wtc.notification.application;

import com.wtc.shared.config.WtcProperties;
import com.wtc.shared.security.SecretCipher;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Textes des messages (français). Un modèle n'existe que pour les canaux où il a du sens : demander
 * un modèle pour un canal qui n'en a pas rend {@link Optional#empty()}, et la notification est
 * ignorée (pas en erreur).
 *
 * <p>Les valeurs saisies par des personnes (noms) sont échappées dans le HTML. Les jetons à usage
 * unique sont déchiffrés <b>au moment de l'envoi</b> : la file n'en contient jamais la valeur en
 * clair.
 */
@Component
public class NotificationTemplates {

    static final String TOKEN_CONTEXT = "outbox:identity-token";

    private final SecretCipher cipher;
    private final String webBaseUrl;

    NotificationTemplates(SecretCipher cipher, WtcProperties properties) {
        this.cipher = cipher;
        this.webBaseUrl = properties.notification().links().webBaseUrl().replaceAll("/+$", "");
    }

    public Optional<Rendered> render(String template, Channel channel, JsonNode p) {
        return switch (template) {
            case "password-reset" ->
                    channel == Channel.EMAIL ? Optional.of(passwordReset(p)) : Optional.empty();
            case "staff-invitation" ->
                    channel == Channel.EMAIL ? Optional.of(staffInvitation(p)) : Optional.empty();
            case "transfer-created" ->
                    transfer(
                            channel,
                            p,
                            "enregistré",
                            "wtc_transfer_created",
                            "Votre transfert %s de %s vers %s a été enregistré. Nous vous prévenons"
                                    + " dès qu'il est payé.",
                            List.of(
                                    name(p),
                                    s(p, "reference"),
                                    money(p, "sourceAmount", "sourceCurrency"),
                                    s(p, "beneficiaryName")));
            case "transfer-completed" ->
                    transfer(
                            channel,
                            p,
                            "payé",
                            "wtc_transfer_completed",
                            "Bonne nouvelle : votre transfert %s est payé. %s a reçu %s.",
                            List.of(
                                    name(p),
                                    s(p, "reference"),
                                    money(p, "destinationAmount", "destinationCurrency"),
                                    s(p, "beneficiaryName")));
            case "transfer-refunded" ->
                    transfer(
                            channel,
                            p,
                            "remboursé",
                            "wtc_transfer_refunded",
                            "Votre transfert %s n'a pas pu aboutir. %s ont été recrédités sur votre"
                                    + " portefeuille.",
                            List.of(
                                    name(p),
                                    s(p, "reference"),
                                    money(p, "totalDebit", "sourceCurrency")));
            case "kyc-approved" ->
                    channel == Channel.EMAIL ? Optional.of(kyc(p, true)) : Optional.empty();
            case "kyc-rejected" ->
                    channel == Channel.EMAIL ? Optional.of(kyc(p, false)) : Optional.empty();
            case "kyc-expired" ->
                    channel == Channel.EMAIL ? Optional.of(kycExpiry(p, true)) : Optional.empty();
            case "kyc-expiring" ->
                    channel == Channel.EMAIL ? Optional.of(kycExpiry(p, false)) : Optional.empty();
            case "daily-report" ->
                    channel == Channel.TELEGRAM
                            ? Optional.of(
                                    new Rendered(
                                            null,
                                            "Synthèse du " + s(p, "day") + "\n" + s(p, "lines"),
                                            null,
                                            null,
                                            List.of()))
                            : Optional.empty();
            case "ops-alert" ->
                    channel == Channel.TELEGRAM ? Optional.of(alert(p)) : Optional.empty();
            default -> Optional.empty();
        };
    }

    // ------------------------------------------------------------------ comptes

    private Rendered passwordReset(JsonNode p) {
        String link = webBaseUrl + "/auth/reset-password?token=" + urlEncode(token(p));
        String text =
                "Bonjour "
                        + name(p)
                        + ",\n\n"
                        + "Vous avez demandé à réinitialiser votre mot de passe WTC. Ouvrez ce lien"
                        + " (valable jusqu'au "
                        + s(p, "expiresAt")
                        + ") :\n"
                        + link
                        + "\n\n"
                        + "Si vous n'êtes pas à l'origine de cette demande, ignorez ce message :"
                        + " votre mot de passe reste inchangé.\n";
        return email(
                "Réinitialisation de votre mot de passe WTC",
                text,
                link,
                "Réinitialiser mon mot de passe");
    }

    private Rendered staffInvitation(JsonNode p) {
        String link = webBaseUrl + "/auth/reset-password?token=" + urlEncode(token(p));
        String text =
                "Bonjour "
                        + name(p)
                        + ",\n\n"
                        + "Un compte WTC vient d'être créé pour vous. Choisissez votre mot de passe"
                        + " avec ce lien (valable jusqu'au "
                        + s(p, "expiresAt")
                        + ") :\n"
                        + link
                        + "\n\n"
                        + "À votre première connexion, vous devrez activer la double"
                        + " authentification.\n";
        return email("Invitation à rejoindre l'équipe WTC", text, link, "Choisir mon mot de passe");
    }

    private Rendered kyc(JsonNode p, boolean approved) {
        String text =
                approved
                        ? "Bonjour "
                                + name(p)
                                + ",\n\n"
                                + "Votre pièce d'identité a été vérifiée : vous pouvez envoyer de"
                                + " l'argent avec WTC.\n"
                        : "Bonjour "
                                + name(p)
                                + ",\n\nVotre document n'a pas pu être accepté : "
                                + s(p, "reason")
                                + "\n\n"
                                + "Vous pouvez en déposer un nouveau depuis votre espace client.\n";
        return email(
                approved
                        ? "Votre identité est vérifiée"
                        : "Votre document d'identité n'a pas été accepté",
                text,
                null,
                null);
    }

    private static final java.util.Map<String, String> DOCUMENTS =
            java.util.Map.of(
                    "PASSPORT", "passeport",
                    "NATIONAL_ID", "carte nationale d'identité",
                    "RESIDENCE_PERMIT", "titre de séjour",
                    "DRIVING_LICENSE", "permis de conduire",
                    "PROOF_OF_ADDRESS", "justificatif de domicile",
                    "SELFIE", "selfie",
                    "BUSINESS_REGISTRATION", "extrait d'immatriculation");

    private Rendered kycExpiry(JsonNode p, boolean expired) {
        String document = DOCUMENTS.getOrDefault(s(p, "documentType"), "document");
        String date = s(p, "expiresOn");
        String text =
                expired
                        ? "Bonjour "
                                + name(p)
                                + ",\n\nVotre "
                                + document
                                + " a expiré le "
                                + date
                                + ". Vous ne pouvez plus envoyer d'argent tant qu'une pièce valide"
                                + " n'a pas été vérifiée : déposez-en une nouvelle depuis votre"
                                + " espace client.\n"
                        : "Bonjour "
                                + name(p)
                                + ",\n\nVotre "
                                + document
                                + " expire le "
                                + date
                                + ". Pensez à déposer une pièce à jour depuis votre espace client"
                                + " pour continuer à envoyer de l'argent sans interruption.\n";
        return email(
                expired
                        ? "Votre pièce d'identité a expiré"
                        : "Votre pièce d'identité arrive à expiration",
                text,
                null,
                null);
    }

    // ------------------------------------------------------------------ transferts

    private Optional<Rendered> transfer(
            Channel channel,
            JsonNode p,
            String word,
            String whatsappTemplate,
            String pattern,
            List<String> params) {
        return switch (channel) {
            case EMAIL -> {
                String line = String.format(pattern, emailArgs(p, whatsappTemplate));
                yield Optional.of(
                        email(
                                "Votre transfert " + s(p, "reference") + " est " + word,
                                "Bonjour " + name(p) + ",\n\n" + line + "\n",
                                null,
                                null));
            }
            case WHATSAPP -> Optional.of(new Rendered(null, null, null, whatsappTemplate, params));
            case TELEGRAM -> Optional.empty();
        };
    }

    private Object[] emailArgs(JsonNode p, String templateName) {
        return switch (templateName) {
            case "wtc_transfer_created" ->
                    new Object[] {
                        s(p, "reference"),
                        money(p, "sourceAmount", "sourceCurrency"),
                        s(p, "beneficiaryName")
                    };
            case "wtc_transfer_completed" ->
                    new Object[] {
                        s(p, "reference"),
                        s(p, "beneficiaryName"),
                        money(p, "destinationAmount", "destinationCurrency")
                    };
            default -> new Object[] {s(p, "reference"), money(p, "totalDebit", "sourceCurrency")};
        };
    }

    // ------------------------------------------------------------------ alertes

    private Rendered alert(JsonNode p) {
        return new Rendered(
                null, "⚠ " + s(p, "title") + "\n" + s(p, "details"), null, null, List.of());
    }

    // ------------------------------------------------------------------ utilitaires

    private static Rendered email(String subject, String text, String link, String linkLabel) {
        StringBuilder html =
                new StringBuilder("<p>")
                        .append(escape(text).replace("\n\n", "</p><p>").replace("\n", "<br>"))
                        .append("</p>");
        if (link != null) {
            html.append("<p><a href=\"")
                    .append(escape(link))
                    .append("\">")
                    .append(escape(linkLabel))
                    .append("</a></p>");
        }
        return new Rendered(subject, text, html.toString(), null, List.of());
    }

    private String token(JsonNode p) {
        String sealed = s(p, "tokenEncrypted");
        if (sealed.isEmpty()) {
            throw new IllegalStateException("Jeton absent du message");
        }
        return cipher.decrypt(Base64.getDecoder().decode(sealed), TOKEN_CONTEXT);
    }

    private static String name(JsonNode p) {
        String n = s(p, "displayName");
        return n.isEmpty() ? "" : n;
    }

    private static String money(JsonNode p, String amountField, String currencyField) {
        return s(p, amountField) + " " + s(p, currencyField);
    }

    private static String s(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
