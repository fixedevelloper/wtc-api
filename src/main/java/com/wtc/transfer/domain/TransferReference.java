package com.wtc.transfer.domain;

import java.time.Year;

/** Référence lisible d'un transfert : {@code WTC-<année>-<numéro sur 6 chiffres>}. */
public final class TransferReference {

    private TransferReference() {}

    public static String format(Year year, long sequence) {
        return String.format("WTC-%d-%06d", year.getValue(), sequence);
    }
}
