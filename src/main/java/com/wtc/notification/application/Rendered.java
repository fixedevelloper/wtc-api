package com.wtc.notification.application;

import java.util.List;

/**
 * Message prêt à envoyer. Courriel : {@code subject}, {@code text} et {@code html}. WhatsApp : le
 * modèle approuvé chez Meta ({@code whatsappTemplate}) et ses paramètres dans l'ordre. Telegram :
 * {@code text}.
 */
public record Rendered(
        String subject,
        String text,
        String html,
        String whatsappTemplate,
        List<String> whatsappParams) {

    public Rendered {
        whatsappParams = whatsappParams == null ? List.of() : List.copyOf(whatsappParams);
    }

    @Override
    public String toString() {
        return "Rendered[" + subject + "]"; // le texte peut contenir un lien secret
    }
}
