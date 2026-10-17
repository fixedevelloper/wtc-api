package com.wtc.wallet.application;

/** Nature d'une transaction du grand livre (valeurs autorisées par la base). */
public enum LedgerKind {
    OPENING_BALANCE,
    TOP_UP,
    TRANSFER_DEBIT,
    TRANSFER_REFUND,
    ADJUSTMENT,
    CORRECTION
}
