package com.wtc.wallet.application.port;

import com.wtc.wallet.application.PostingRequest;
import com.wtc.wallet.domain.Direction;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface LedgerRepository {

    record Existing(long id, UUID publicId, String kind, BigDecimal totalDebits) {}

    /**
     * @return l'identifiant et l'UUID de la transaction créée, ou vide si la référence existe déjà
     *     (aucun effet)
     */
    Optional<Existing> insertTransaction(PostingRequest request);

    Optional<Existing> findByReference(String reference);

    long walletAccountId(long walletId);

    /** Compte de la plateforme pour une devise ; créé à la demande s'il manque. */
    long platformAccountId(String baseCode, String currency);

    void insertEntry(
            long transactionId,
            long accountId,
            Direction direction,
            BigDecimal amount,
            String currency);

    /**
     * Applique le mouvement au solde matérialisé en une instruction conditionnelle : un débit exige
     * un portefeuille actif et un solde suffisant, un crédit un portefeuille non clos. Faux si la
     * condition n'est pas remplie.
     */
    boolean applyToWallet(long walletId, Direction direction, BigDecimal amount, String currency);

    /** Diagnostic d'un refus : (statut, solde, devise) du portefeuille, vide s'il n'existe pas. */
    Optional<WalletStateRow> walletState(long walletId);

    record WalletStateRow(String status, BigDecimal balance, String currency) {}
}
