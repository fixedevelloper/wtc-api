package com.wtc.wallet.application;

import java.util.UUID;

/**
 * Résultat d'un enregistrement ; {@code replayed} est vrai si la référence existait déjà (rien n'a
 * été écrit).
 */
public record PostingResult(long ledgerTransactionId, UUID publicId, boolean replayed) {}
