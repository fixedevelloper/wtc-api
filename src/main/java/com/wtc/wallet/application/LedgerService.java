package com.wtc.wallet.application;

import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.wallet.application.port.LedgerRepository;
import com.wtc.wallet.application.port.LedgerRepository.Existing;
import com.wtc.wallet.application.port.LedgerRepository.WalletStateRow;
import com.wtc.wallet.domain.Direction;
import com.wtc.wallet.domain.PostingRules;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Seule porte d'entrée de tout mouvement d'argent.</b> Une transaction du grand livre est
 * écrite, avec ses écritures et la mise à jour des soldes, dans <i>une seule</i> transaction SQL
 * (celle de l'appelant : le débit d'un transfert et la création du transfert réussissent ou
 * échouent ensemble).
 *
 * <ul>
 *   <li><b>Idempotent</b> : la {@code reference} est unique ; un rejeu identique ne crée rien, un
 *       rejeu au contenu différent est refusé ({@code LEDGER_CONFLICT}).
 *   <li><b>Jamais de solde négatif</b> : le débit est conditionnel en base ({@code balance >=
 *       montant}), donc sûr sous concurrence ; deux débits simultanés ne peuvent pas dépasser le
 *       solde.
 *   <li><b>Sans interblocage</b> : les portefeuilles sont mis à jour par identifiant croissant.
 *   <li><b>Équilibré</b> : vérifié ici avant d'écrire, puis par la base au COMMIT.
 * </ul>
 */
@Service
public class LedgerService {

    private final LedgerRepository ledger;

    LedgerService(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    @Transactional
    public PostingResult post(PostingRequest request) {
        PostingRules.requireValid(
                request.legs().stream()
                        .map(l -> new PostingRules.Line(l.direction(), l.amount(), l.currency()))
                        .toList());

        Optional<Existing> created = ledger.insertTransaction(request);
        if (created.isEmpty()) {
            return replay(request);
        }
        Existing tx = created.get();
        List<PostingLeg> ordered =
                request.legs().stream()
                        .sorted(Comparator.comparing(LedgerService::lockOrder))
                        .toList();
        for (PostingLeg leg : ordered) {
            long accountId =
                    switch (leg.account()) {
                        case AccountRef.Wallet w -> ledger.walletAccountId(w.walletId());
                        case AccountRef.Platform p ->
                                ledger.platformAccountId(p.baseCode(), leg.currency());
                    };
            if (leg.account() instanceof AccountRef.Wallet w
                    && !ledger.applyToWallet(
                            w.walletId(), leg.direction(), leg.amount(), leg.currency())) {
                throw refusal(w.walletId(), leg);
            }
            ledger.insertEntry(tx.id(), accountId, leg.direction(), leg.amount(), leg.currency());
        }
        return new PostingResult(tx.id(), tx.publicId(), false);
    }

    /**
     * Portefeuilles d'abord, par identifiant croissant ; les comptes de la plateforme n'ont pas de
     * solde à verrouiller.
     */
    private static long lockOrder(PostingLeg leg) {
        return leg.account() instanceof AccountRef.Wallet w ? w.walletId() : Long.MAX_VALUE;
    }

    private PostingResult replay(PostingRequest request) {
        Existing existing =
                ledger.findByReference(request.reference())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Référence en conflit mais introuvable : "
                                                        + request.reference()));
        BigDecimal debits =
                request.legs().stream()
                        .filter(l -> l.direction() == Direction.DEBIT)
                        .map(PostingLeg::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!existing.kind().equals(request.kind().name())
                || existing.totalDebits().compareTo(debits) != 0) {
            throw new DomainException(
                    ErrorCode.LEDGER_CONFLICT,
                    "Cette référence est déjà utilisée par une écriture différente.");
        }
        return new PostingResult(existing.id(), existing.publicId(), true);
    }

    /** Explique pourquoi un mouvement de portefeuille a été refusé. */
    private DomainException refusal(long walletId, PostingLeg leg) {
        Optional<WalletStateRow> state = ledger.walletState(walletId);
        if (state.isEmpty()) {
            return new DomainException(ErrorCode.WALLET_NOT_FOUND, "Portefeuille introuvable.");
        }
        WalletStateRow wallet = state.get();
        if (!wallet.currency().equals(leg.currency())) {
            return new DomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Devise du mouvement différente de celle du portefeuille.");
        }
        if (leg.direction() == Direction.DEBIT && wallet.status().equals("ACTIVE")) {
            return new DomainException(ErrorCode.INSUFFICIENT_FUNDS, "Solde insuffisant.");
        }
        return new DomainException(
                ErrorCode.WALLET_NOT_ACTIVE,
                "Ce portefeuille n'accepte pas ce mouvement (" + wallet.status() + ").");
    }
}
