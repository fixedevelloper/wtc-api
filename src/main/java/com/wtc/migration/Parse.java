package com.wtc.migration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Nettoyage des valeurs de l'ancienne base. Chaque fonction renvoie « inexploitable » (vide/null)
 * plutôt que de deviner.
 */
final class Parse {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{6,14}$");
    private static final List<DateTimeFormatter> DATE_FORMATS =
            List.of(
                    DateTimeFormatter.ISO_LOCAL_DATE,
                    DateTimeFormatter.ofPattern("dd/MM/uuuu"),
                    DateTimeFormatter.ofPattern("d/M/uuuu"),
                    DateTimeFormatter.ofPattern("dd-MM-uuuu"),
                    DateTimeFormatter.ofPattern("d-M-uuuu"),
                    DateTimeFormatter.ofPattern("uuuu/MM/dd"),
                    DateTimeFormatter.ofPattern("dd.MM.uuuu"));

    private Parse() {}

    /**
     * Date au format ISO ou jj/mm/aaaa (et variantes) ; les dates de naissance futures ou avant
     * 1900 sont refusées.
     */
    static Optional<LocalDate> date(String raw, LocalDate today) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.strip();
        if (s.length() > 10 && s.charAt(10) == 'T' || s.length() > 10 && s.charAt(10) == ' ') {
            s = s.substring(0, 10);
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                LocalDate d = LocalDate.parse(s, f);
                if (d.getYear() < 1900 || d.isAfter(today)) {
                    return Optional.empty();
                }
                return Optional.of(d);
            } catch (DateTimeParseException ignored) {
                // format suivant
            }
        }
        return Optional.empty();
    }

    /**
     * Date d'expiration d'une pièce : le futur est normal, seules les valeurs absurdes sont
     * refusées.
     */
    static Optional<LocalDate> expiry(String raw) {
        return date(raw, LocalDate.now().plusYears(60));
    }

    /**
     * Numéro E.164 : {@code +} suivi de chiffres ; sinon le numéro national est complété par
     * l'indicatif du pays (retrait du 0 initial) à condition que le résultat soit plausible. Un
     * numéro déjà précédé de l'indicatif est reconnu.
     */
    static Optional<String> phone(String raw, String callingCode) {
        if (raw == null) {
            return Optional.empty();
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || raw.chars().filter(Character::isLetter).count() > 0) {
            return Optional.empty();
        }
        String candidate;
        if (raw.strip().startsWith("+")) {
            candidate = "+" + digits;
        } else if (raw.strip().startsWith("00")) {
            candidate = "+" + digits.substring(2);
        } else {
            String code = callingCode == null ? "" : callingCode.replaceAll("[^0-9]", "");
            if (!code.isEmpty()
                    && digits.startsWith(code)
                    && digits.length() >= code.length() + 6) {
                candidate = "+" + digits;
            } else if (!code.isEmpty()) {
                candidate = "+" + code + digits.replaceFirst("^0+", "");
            } else {
                return Optional.empty();
            }
        }
        return E164.matcher(candidate).matches() ? Optional.of(candidate) : Optional.empty();
    }

    /** Rôles Symfony : liste JSON, parfois imbriquée ({@code [["ROLE_USER"]]}). */
    static Set<String> roles(String json) {
        Set<String> out = new LinkedHashSet<>();
        if (json == null) {
            return out;
        }
        try {
            collect(JSON.readTree(json), out);
        } catch (RuntimeException e) {
            // JSON illisible : aucun rôle, signalé par l'appelant
        }
        return out;
    }

    private static void collect(JsonNode node, Set<String> out) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            node.forEach(n -> collect(n, out));
        } else if (node.isString()) {
            out.add(node.asString().strip().toUpperCase(Locale.ROOT));
        }
    }

    static String gender(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (s.equals("m")
                || s.startsWith("masc")
                || s.startsWith("hom")
                || s.equals("male")
                || s.equals("mr")) {
            return "M";
        }
        if (s.equals("f")
                || s.startsWith("fem")
                || s.startsWith("fém")
                || s.equals("female")
                || s.equals("mme")
                || s.equals("mlle")) {
            return "F";
        }
        return null;
    }

    /**
     * Type de pièce d'identité → type du nouveau modèle ({@code KycDocumentType}), nul si inconnu.
     */
    static String documentType(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (s.contains("rccm") || s.contains("registre") || s.contains("business")) {
            return "BUSINESS_REGISTRATION";
        }
        if (s.contains("pass") || s.equals("pp")) {
            return "PASSPORT";
        }
        if (s.contains("sejour")
                || s.contains("séjour")
                || s.contains("residence")
                || s.contains("résidence")) {
            return "RESIDENCE_PERMIT";
        }
        if (s.contains("permis") || s.contains("driving")) {
            return "DRIVING_LICENSE";
        }
        if (s.contains("cni")
                || s.contains("carte")
                || s.contains("national")
                || s.contains("identit")
                || s.contains("id")) {
            return "NATIONAL_ID";
        }
        return null;
    }

    /**
     * Type de transfert libre (≈ 30 variantes) → BANK_TRANSFER ou MOBILE_MONEY ; nul si
     * indéterminable.
     */
    static String transferKind(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (s.contains("bank")
                || s.contains("banc")
                || s.contains("virement")
                || s.contains("carte")) {
            return "BANK_TRANSFER";
        }
        if (s.contains("mobil")
                || s.contains("wave")
                || s.contains("rechr")
                || s.contains("momo")
                || s.contains("orange")
                || s.contains("mtn")) {
            return "MOBILE_MONEY";
        }
        return null;
    }

    /** Devise normalisée en ISO 4217 (les anciens libellés « £ » et « Naira »). */
    static String currency(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        return switch (s) {
            case "£" -> "GBP";
            case "Naira" -> "NGN";
            default -> s.matches("[A-Za-z]{3}") ? s.toUpperCase(Locale.ROOT) : null;
        };
    }

    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip().replaceAll("\\s+", " ");
        return s.isEmpty() ? null : s;
    }

    static boolean validE164(String phone) {
        return phone != null && E164.matcher(phone).matches();
    }
}
