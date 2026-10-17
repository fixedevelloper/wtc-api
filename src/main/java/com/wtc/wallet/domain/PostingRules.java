package com.wtc.wallet.domain;

import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Map;

/**
 * Règles d'une transaction du grand livre, vérifiées <b>avant</b> d'écrire (la base les revérifie
 * au COMMIT) : au moins deux écritures, montants strictement positifs, pas plus de décimales que la
 * devise n'en admet, et, pour chaque devise, total des débits = total des crédits.
 */
public final class PostingRules {

    /** Écriture réduite à ce qui compte pour la validation. */
    public record Line(Direction direction, BigDecimal amount, String currency) {}

    private PostingRules() {}

    public static void requireValid(List<Line> lines) {
        if (lines.size() < 2) {
            throw bug("Une transaction compte au moins deux écritures.");
        }
        Map<String, BigDecimal> balance = new java.util.TreeMap<>();
        for (Line line : lines) {
            requireAmount(line.amount(), line.currency());
            BigDecimal signed =
                    line.direction() == Direction.DEBIT ? line.amount() : line.amount().negate();
            balance.merge(line.currency(), signed, BigDecimal::add);
        }
        String unbalanced =
                balance.entrySet().stream()
                        .filter(e -> e.getValue().signum() != 0)
                        .map(e -> e.getKey() + " (écart " + e.getValue().toPlainString() + ")")
                        .collect(java.util.stream.Collectors.joining(", "));
        if (!unbalanced.isEmpty()) {
            throw bug("Écritures déséquilibrées en " + unbalanced + ".");
        }
    }

    /** Montant strictement positif, avec au plus les décimales de la devise. */
    public static void requireAmount(BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            throw invalid("amount", "Positive", "Le montant doit être strictement positif.");
        }
        int digits;
        try {
            if (currency == null) {
                throw new IllegalArgumentException("devise absente");
            }
            digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        } catch (IllegalArgumentException e) {
            throw invalid("currency", "UnknownCurrency", "Devise inconnue.");
        }
        if (amount.stripTrailingZeros().scale() > digits) {
            throw invalid(
                    "amount",
                    "TooManyDecimals",
                    "Cette devise n'admet que " + digits + " décimale(s).");
        }
        if (amount.precision() - amount.scale() > 15) {
            throw invalid("amount", "TooLarge", "Montant trop élevé.");
        }
    }

    private static DomainException invalid(String field, String code, String message) {
        return new DomainException(
                ErrorCode.VALIDATION_FAILED,
                message,
                List.of(new FieldViolation(field, code, message)));
    }

    /**
     * Une erreur de programmation (jamais une erreur de l'utilisateur) : transaction mal composée.
     */
    private static IllegalStateException bug(String message) {
        return new IllegalStateException(message);
    }
}
