package com.wtc.wallet.application;

import com.wtc.wallet.domain.Direction;
import java.math.BigDecimal;

/**
 * Une écriture d'une transaction : sur quel compte, dans quel sens, combien et en quelle devise.
 */
public record PostingLeg(
        AccountRef account, Direction direction, BigDecimal amount, String currency) {

    public static PostingLeg debit(AccountRef account, BigDecimal amount, String currency) {
        return new PostingLeg(account, Direction.DEBIT, amount, currency);
    }

    public static PostingLeg credit(AccountRef account, BigDecimal amount, String currency) {
        return new PostingLeg(account, Direction.CREDIT, amount, currency);
    }
}
