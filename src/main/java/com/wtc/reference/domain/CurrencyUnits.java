package com.wtc.reference.domain;

import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;

/**
 * Unités monétaires : nombre de décimales d'une devise (XAF et XOF : 0, EUR et USD : 2…) et
 * arrondi.
 */
public final class CurrencyUnits {

    private CurrencyUnits() {}

    /**
     * @throws DomainException {@code VALIDATION_FAILED} si le code n'est pas une devise ISO 4217
     */
    public static int fractionDigits(String currencyCode) {
        try {
            if (currencyCode == null) {
                throw new IllegalArgumentException("devise absente");
            }
            int digits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
            return Math.max(digits, 0);
        } catch (IllegalArgumentException e) {
            throw new DomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Devise inconnue : " + currencyCode,
                    List.of(
                            new FieldViolation(
                                    "currency",
                                    "UnknownCurrency",
                                    "Code devise ISO 4217 attendu.")));
        }
    }

    public static BigDecimal round(BigDecimal amount, String currencyCode, RoundingMode mode) {
        return amount.setScale(fractionDigits(currencyCode), mode);
    }
}
