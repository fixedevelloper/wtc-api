package com.wtc.wallet.domain;

/**
 * Sens d'une écriture. Pour un portefeuille client (compte de passif), {@code CREDIT} augmente le
 * solde (argent qui entre) et {@code DEBIT} le diminue (argent qui sort).
 */
public enum Direction {
    DEBIT,
    CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
