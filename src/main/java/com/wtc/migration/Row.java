package com.wtc.migration;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Ligne de l'ancienne base, avec des accesseurs tolérants (les données source sont sales : texte
 * vide, null, types mélangés).
 */
record Row(Map<String, Object> values) {

    /** Fuseau de l'ancien serveur : les dates MySQL n'ont pas de fuseau. */
    static final ZoneId LEGACY_ZONE = ZoneId.of("Africa/Brazzaville");

    Object raw(String column) {
        return values.get(column);
    }

    /** Texte rogné ; vide ou absent : null. */
    String str(String column) {
        Object v = values.get(column);
        if (v == null) {
            return null;
        }
        String s =
                (v instanceof byte[] b
                                ? new String(b, java.nio.charset.StandardCharsets.UTF_8)
                                : v.toString())
                        .strip();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    Long id(String column) {
        Object v = values.get(column);
        return v instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }

    long idOrZero() {
        return id("id") == null ? 0L : id("id");
    }

    Integer integer(String column) {
        Object v = values.get(column);
        return v instanceof Number n ? Integer.valueOf(n.intValue()) : null;
    }

    boolean bool(String column) {
        Object v = values.get(column);
        if (v instanceof Boolean b) {
            return b;
        }
        return v instanceof Number n && n.intValue() != 0;
    }

    /** Décimal exact d'un double source (représentation la plus courte : 0.1 reste 0.1). */
    BigDecimal decimal(String column) {
        Object v = values.get(column);
        if (v instanceof BigDecimal d) {
            return d;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return null;
    }

    Instant instant(String column) {
        Object v = values.get(column);
        if (v instanceof Timestamp t) {
            return t.toLocalDateTime().atZone(LEGACY_ZONE).toInstant();
        }
        if (v instanceof LocalDateTime l) {
            return l.atZone(LEGACY_ZONE).toInstant();
        }
        if (v instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        return null;
    }

    /**
     * Colonnes dont la valeur ne doit jamais figurer en clair dans le rapport (numéros de pièce et
     * de compte, secrets).
     */
    private static final java.util.Set<String> SENSITIVE =
            java.util.Set.of(
                    "password",
                    "pin_code",
                    "numeropiece",
                    "id_number",
                    "bankaccountnumber",
                    "bankiban",
                    "bank_account_number",
                    "bank_iban",
                    "token",
                    "authy_id",
                    "fcm_token",
                    "chat_id",
                    "banksignature",
                    "bankroutingnumber");

    /**
     * Copie de la ligne pour le rapport (valeurs d'origine). Les colonnes sensibles sont masquées
     * (quatre derniers caractères au plus) : le rapport circule entre personnes qui n'ont pas à
     * voir ces numéros.
     */
    Map<String, Object> forReport(String... hide) {
        Set<String> extra = Set.of(hide);
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach(
                (k, v) -> {
                    if (v == null) {
                        copy.put(k, null);
                    } else if (SENSITIVE.contains(k) || extra.contains(k)) {
                        String text =
                                v instanceof byte[] b
                                        ? new String(b, java.nio.charset.StandardCharsets.UTF_8)
                                        : v.toString();
                        copy.put(
                                k,
                                text.length() > 6 && !"password".equals(k) && !"token".equals(k)
                                        ? "****" + text.substring(text.length() - 4)
                                        : "***");
                    } else {
                        copy.put(
                                k,
                                v instanceof byte[] b
                                        ? new String(b, java.nio.charset.StandardCharsets.UTF_8)
                                        : v.toString());
                    }
                });
        return copy;
    }
}
