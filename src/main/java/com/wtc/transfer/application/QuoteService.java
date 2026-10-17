package com.wtc.transfer.application;

import com.wtc.customer.application.DestinationService;
import com.wtc.customer.application.DestinationView;
import com.wtc.reference.application.PricingQuote;
import com.wtc.reference.application.PricingService;
import com.wtc.wallet.application.WalletService;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Devis d'un transfert : le serveur fait foi pour les frais, le taux et le total ; le client n'en
 * calcule plus aucun. La devise de départ est celle du portefeuille principal du client ; le barème
 * dépend du pays de la destination.
 */
@Service
public class QuoteService {

    /** Devis et situation du portefeuille source. */
    public record Quote(
            PricingQuote pricing, BigDecimal availableBalance, boolean sufficientFunds) {}

    private final PricingService pricing;
    private final WalletService wallets;
    private final DestinationService destinations;

    QuoteService(PricingService pricing, WalletService wallets, DestinationService destinations) {
        this.pricing = pricing;
        this.wallets = wallets;
        this.destinations = destinations;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public Quote forCustomer(UUID customerId, UUID destinationId, BigDecimal sourceAmount) {
        return quote(customerId, destinationId, sourceAmount);
    }

    /** Devis établi par le back-office pour le compte d'un client (saisie d'un transfert). */
    @PreAuthorize("hasAuthority('TRANSFER_CREATE')")
    @Transactional(readOnly = true)
    public Quote forCustomerByStaff(UUID customerId, UUID destinationId, BigDecimal sourceAmount) {
        return quote(customerId, destinationId, sourceAmount);
    }

    private Quote quote(UUID customerId, UUID destinationId, BigDecimal sourceAmount) {
        DestinationView destination = destinations.resolve(customerId, destinationId);
        String currency = wallets.primaryCurrency(customerId);
        PricingQuote priced = pricing.quote(currency, destination.countryCode(), sourceAmount);
        BigDecimal available = wallets.availableBalance(customerId, currency);
        return new Quote(priced, available, available.compareTo(priced.totalDebit()) >= 0);
    }
}
