package com.wtc.reference.application;

/**
 * Ce qu'il faut pour router un envoi bancaire chez Wace : code « payeur » du pays, identifiant et
 * code de la banque. Tous les champs viennent des référentiels maintenus avec Wace ; s'ils
 * manquent, l'envoi est impossible.
 */
public record BankRouting(String payerCode, String bankName, Long waceBankId, String bankCode) {}
