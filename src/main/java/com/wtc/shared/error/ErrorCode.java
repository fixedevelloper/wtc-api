package com.wtc.shared.error;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Codes d'erreur applicatifs stables, exposés dans le champ {@code code} des réponses {@code
 * problem+json}. Les clients se branchent sur ce code, jamais sur le message. Un test vérifie que
 * cette liste est identique à l'énumération {@code ErrorCode} du contrat OpenAPI : ajouter un code
 * ici impose de l'ajouter au contrat.
 */
public enum ErrorCode {
    // Transverses
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Requête mal formée"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentification requise"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Accès refusé"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Ressource introuvable"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Méthode non autorisée"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Type de contenu non pris en charge"),
    CONFLICT(HttpStatus.CONFLICT, "Conflit d'état"),
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "Validation échouée"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Trop de requêtes"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur interne"),

    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Fichier trop volumineux"),

    // Référentiels
    COUNTRY_NOT_FOUND(HttpStatus.NOT_FOUND, "Pays introuvable"),

    // Portefeuilles et grand livre
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "Portefeuille introuvable"),
    WALLET_NOT_ACTIVE(HttpStatus.CONFLICT, "Portefeuille non actif"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT, "Solde insuffisant"),
    ADJUSTMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Ajustement introuvable"),
    ADJUSTMENT_ALREADY_DECIDED(HttpStatus.CONFLICT, "Ajustement déjà traité"),
    FOUR_EYES_REQUIRED(HttpStatus.FORBIDDEN, "Une autre personne doit valider"),
    IDEMPOTENCY_KEY_REUSED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "Clé d'idempotence déjà utilisée avec un autre contenu"),
    LEDGER_CONFLICT(HttpStatus.CONFLICT, "Écriture déjà enregistrée avec un contenu différent"),

    // Transferts
    TRANSFER_NOT_FOUND(HttpStatus.NOT_FOUND, "Transfert introuvable"),
    TRANSFER_NOT_REFUNDABLE(HttpStatus.CONFLICT, "Transfert non remboursable"),
    KYC_REQUIRED(HttpStatus.FORBIDDEN, "Vérification d'identité requise"),
    PARTY_INCOMPLETE(HttpStatus.UNPROCESSABLE_CONTENT, "Informations incomplètes pour l'envoi"),
    QUOTE_CHANGED(HttpStatus.CONFLICT, "Le devis a changé"),
    AMOUNT_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Plafond de transfert dépassé"),

    PROVIDER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "Fournisseur de paiement indisponible"),

    // Tarification
    FEE_NOT_CONFIGURED(HttpStatus.UNPROCESSABLE_CONTENT, "Aucun barème de frais pour ce montant"),
    EXCHANGE_RATE_UNAVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT, "Taux de change indisponible"),
    DESTINATION_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_CONTENT, "Destination non prise en charge"),
    FEE_SCHEDULE_OVERLAP(HttpStatus.CONFLICT, "Tranche en chevauchement"),
    FEE_SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "Tranche de barème introuvable"),
    PRICING_ZONE_EXISTS(HttpStatus.CONFLICT, "Code de zone déjà utilisé"),
    EXCHANGE_RATE_EXISTS(HttpStatus.CONFLICT, "Un taux existe déjà pour cette paire à cette date"),
    SETTING_NOT_FOUND(HttpStatus.NOT_FOUND, "Paramètre introuvable"),

    // Clients, KYC, expéditeurs et bénéficiaires
    CUSTOMER_NOT_FOUND(HttpStatus.NOT_FOUND, "Client introuvable"),
    CUSTOMER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "Compte client non actif"),
    PARTY_NOT_FOUND(HttpStatus.NOT_FOUND, "Expéditeur ou bénéficiaire introuvable"),
    DESTINATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Destination de paiement introuvable"),
    KYC_DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Document KYC introuvable"),
    KYC_DOCUMENT_ALREADY_REVIEWED(HttpStatus.CONFLICT, "Document KYC déjà examiné"),
    KYC_LOCKED(HttpStatus.CONFLICT, "Identité vérifiée : modification impossible"),
    WEB_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "Jeton web introuvable"),
    WEB_TOKEN_UNAVAILABLE(HttpStatus.CONFLICT, "Jeton web déjà utilisé ou expiré"),
    UNSUPPORTED_FILE_TYPE(HttpStatus.UNPROCESSABLE_CONTENT, "Type de fichier non pris en charge"),
    EMAIL_CHANGE_UNSUPPORTED(
            HttpStatus.UNPROCESSABLE_CONTENT, "Changement d'adresse e-mail non pris en charge"),

    // Identité
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Identifiants invalides"),
    INVALID_MFA_CODE(HttpStatus.UNAUTHORIZED, "Code de second facteur invalide"),
    MFA_CODE_REJECTED(HttpStatus.UNPROCESSABLE_CONTENT, "Code de second facteur refusé"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Refresh token invalide ou expiré"),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "Refresh token déjà utilisé : session révoquée"),
    INVALID_RESET_TOKEN(
            HttpStatus.UNPROCESSABLE_CONTENT, "Lien de réinitialisation invalide ou expiré"),
    INVALID_CURRENT_PASSWORD(HttpStatus.UNPROCESSABLE_CONTENT, "Mot de passe actuel incorrect"),
    WEAK_PASSWORD(HttpStatus.UNPROCESSABLE_CONTENT, "Mot de passe trop faible"),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Adresse e-mail déjà utilisée"),
    PHONE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Numéro de téléphone déjà utilisé"),
    MFA_ALREADY_ENABLED(HttpStatus.CONFLICT, "Second facteur déjà activé"),
    MFA_NOT_ENROLLED(HttpStatus.CONFLICT, "Aucun second facteur en cours d'activation"),
    MFA_REQUIRED_FOR_STAFF(
            HttpStatus.FORBIDDEN, "Le second facteur est obligatoire pour le personnel"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "Utilisateur introuvable"),
    LAST_SUPER_ADMIN(HttpStatus.CONFLICT, "Il doit rester au moins un super-administrateur actif"),
    SELF_MODIFICATION_FORBIDDEN(
            HttpStatus.FORBIDDEN, "Vous ne pouvez pas modifier votre propre accès");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /**
     * Fragment d'URI du type d'erreur : {@code COUNTRY_NOT_FOUND} devient {@code
     * country-not-found}.
     */
    public String slug() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
