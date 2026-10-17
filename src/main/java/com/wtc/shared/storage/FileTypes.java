package com.wtc.shared.storage;

import java.util.Optional;

/**
 * Détecte le vrai type d'un fichier par ses premiers octets. L'en-tête {@code Content-Type} envoyé
 * par le client ne prouve rien : on ne stocke que ce qu'on a reconnu, sous le type reconnu.
 */
public final class FileTypes {

    private FileTypes() {}

    public enum Kind {
        JPEG("image/jpeg"),
        PNG("image/png"),
        PDF("application/pdf");

        private final String mediaType;

        Kind(String mediaType) {
            this.mediaType = mediaType;
        }

        public String mediaType() {
            return mediaType;
        }

        public boolean isImage() {
            return this != PDF;
        }
    }

    public static Optional<Kind> detect(byte[] data) {
        if (data == null || data.length < 5) {
            return Optional.empty();
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return Optional.of(Kind.JPEG);
        }
        if (data.length >= 8
                && (data[0] & 0xFF) == 0x89
                && data[1] == 'P'
                && data[2] == 'N'
                && data[3] == 'G'
                && data[4] == 0x0D
                && data[5] == 0x0A
                && data[6] == 0x1A
                && data[7] == 0x0A) {
            return Optional.of(Kind.PNG);
        }
        if (data[0] == '%'
                && data[1] == 'P'
                && data[2] == 'D'
                && data[3] == 'F'
                && data[4] == '-') {
            return Optional.of(Kind.PDF);
        }
        return Optional.empty();
    }
}
