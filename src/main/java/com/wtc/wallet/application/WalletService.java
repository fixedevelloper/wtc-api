package com.wtc.wallet.application;

import com.wtc.customer.application.CustomerAccounts;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.wallet.application.port.WalletRepository;
import com.wtc.wallet.application.port.WalletRepository.WalletRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portefeuilles d'un client : un par devise, créés au besoin. Le portefeuille « principal » est
 * celui de la devise du pays de résidence du client ; c'est lui que renvoient les routes sans
 * paramètre {@code currency}.
 */
@Service
public class WalletService {

    private static final String FALLBACK_CURRENCY = "XAF";

    private final WalletRepository wallets;
    private final CustomerAccounts customers;

    WalletService(WalletRepository wallets, CustomerAccounts customers) {
        this.wallets = wallets;
        this.customers = customers;
    }

    // ------------------------------------------------------------------ pour le client connecté

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public WalletView mine(UUID customerId, String currency) {
        return view(requireWallet(customerId, currency));
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public List<WalletView> mineAll(UUID customerId) {
        return wallets.listOfCustomer(customerId).stream().map(WalletService::view).toList();
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public Page<LedgerEntryView> myEntries(
            UUID customerId, String currency, int page, int size, boolean newestFirst) {
        return wallets.entries(requireWallet(customerId, currency).id(), page, size, newestFirst);
    }

    // ------------------------------------------------------------------ back-office

    @PreAuthorize("hasAuthority('CUSTOMER_READ')")
    @Transactional
    public WalletView ofCustomer(UUID customerId, String currency) {
        return view(requireWallet(customerId, currency));
    }

    @PreAuthorize("hasAuthority('CUSTOMER_READ')")
    @Transactional(readOnly = true)
    public List<WalletView> allOfCustomer(UUID customerId) {
        customers.requireExists(customerId);
        return wallets.listOfCustomer(customerId).stream().map(WalletService::view).toList();
    }

    @PreAuthorize("hasAuthority('CUSTOMER_READ')")
    @Transactional
    public Page<LedgerEntryView> entriesOfCustomer(
            UUID customerId, String currency, int page, int size, boolean newestFirst) {
        return wallets.entries(requireWallet(customerId, currency).id(), page, size, newestFirst);
    }

    // ------------------------------------------------------------------ pour les autres modules

    /** Solde disponible (zéro si le portefeuille n'existe pas encore). Ne crée rien. */
    @Transactional(readOnly = true)
    public BigDecimal availableBalance(UUID customerId, String currency) {
        return wallets.find(customerId, currency.toUpperCase(Locale.ROOT))
                .map(WalletRow::balance)
                .orElse(BigDecimal.ZERO);
    }

    /** Devise du portefeuille principal d'un client. */
    @Transactional(readOnly = true)
    public String primaryCurrency(UUID customerId) {
        return customers.homeCurrency(customerId).orElse(FALLBACK_CURRENCY);
    }

    /**
     * Portefeuille de ce client dans cette devise, créé s'il manque. Réservé aux appels internes
     * déjà autorisés.
     */
    @Transactional
    public WalletRow ensureWallet(UUID customerId, String currency) {
        return wallets.ensure(customerId, currency.toUpperCase(Locale.ROOT))
                .orElseThrow(
                        () ->
                                new DomainException(
                                        ErrorCode.CUSTOMER_NOT_FOUND, "Client introuvable."));
    }

    private WalletRow requireWallet(UUID customerId, String currency) {
        String code =
                currency == null || currency.isBlank()
                        ? primaryCurrency(customerId)
                        : currency.toUpperCase(Locale.ROOT);
        return ensureWallet(customerId, code);
    }

    private static WalletView view(WalletRow w) {
        return new WalletView(w.publicId(), w.currency(), w.balance(), w.status());
    }
}
