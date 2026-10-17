package com.wtc.wallet.application.port;

import com.wtc.wallet.application.LedgerEntryView;
import com.wtc.wallet.application.Page;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository {

    record WalletRow(
            long id,
            UUID publicId,
            long customerId,
            UUID customerPublicId,
            String currency,
            BigDecimal balance,
            String status) {}

    /**
     * Crée le portefeuille (et son compte du grand livre) s'il manque ; vide si le client n'existe
     * pas.
     */
    Optional<WalletRow> ensure(UUID customerPublicId, String currency);

    Optional<WalletRow> find(UUID customerPublicId, String currency);

    List<WalletRow> listOfCustomer(UUID customerPublicId);

    Page<LedgerEntryView> entries(long walletId, int page, int size, boolean newestFirst);
}
