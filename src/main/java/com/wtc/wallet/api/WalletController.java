package com.wtc.wallet.api;

import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.web.PageParams;
import com.wtc.shared.web.PageResponse;
import com.wtc.shared.web.Paged;
import com.wtc.wallet.application.LedgerEntryView;
import com.wtc.wallet.application.WalletService;
import com.wtc.wallet.application.WalletView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Portefeuilles et relevés : le sien (client) et ceux d'un client (back-office, {@code
 * CUSTOMER_READ}).
 */
@RestController
@RequestMapping("/api")
class WalletController {

    private static final Set<String> ENTRY_SORT = Set.of("occurredAt");

    private final WalletService wallets;

    WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    record WalletResponse(UUID id, String currency, String balance, String status) {
        static WalletResponse from(WalletView v) {
            return new WalletResponse(
                    v.id(), v.currency(), v.balance().toPlainString(), v.status());
        }
    }

    record Money(String amount, String currency) {}

    record EntryResponse(
            long id,
            Instant occurredAt,
            String kind,
            String direction,
            Money amount,
            String description,
            String reference) {
        static EntryResponse from(LedgerEntryView v) {
            return new EntryResponse(
                    v.id(),
                    v.occurredAt(),
                    v.kind(),
                    v.direction(),
                    new Money(v.amount().toPlainString(), v.currency()),
                    v.description(),
                    v.reference());
        }
    }

    // ------------------------------------------------------------------ client

    @GetMapping("/me/wallet")
    WalletResponse myWallet(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z]{3}$") String currency) {
        return WalletResponse.from(wallets.mine(customerId(jwt), currency));
    }

    @GetMapping("/me/wallets")
    List<WalletResponse> myWallets(@AuthenticationPrincipal Jwt jwt) {
        return wallets.mineAll(customerId(jwt)).stream().map(WalletResponse::from).toList();
    }

    @GetMapping("/me/wallet/entries")
    PageResponse<EntryResponse> myEntries(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        PageParams order = PageParams.parse(sort, ENTRY_SORT, "occurredAt", false);
        var result = wallets.myEntries(customerId(jwt), currency, page, size, !order.ascending());
        return Paged.of(
                result.content().stream().map(EntryResponse::from).toList(),
                page,
                size,
                result.total());
    }

    // ------------------------------------------------------------------ back-office

    @GetMapping("/admin/customers/{customerId}/wallet")
    WalletResponse customerWallet(
            @PathVariable UUID customerId,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z]{3}$") String currency) {
        return WalletResponse.from(wallets.ofCustomer(customerId, currency));
    }

    @GetMapping("/admin/customers/{customerId}/wallets")
    List<WalletResponse> customerWallets(@PathVariable UUID customerId) {
        return wallets.allOfCustomer(customerId).stream().map(WalletResponse::from).toList();
    }

    @GetMapping("/admin/customers/{customerId}/wallet/entries")
    PageResponse<EntryResponse> customerEntries(
            @PathVariable UUID customerId,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        PageParams order = PageParams.parse(sort, ENTRY_SORT, "occurredAt", false);
        var result =
                wallets.entriesOfCustomer(customerId, currency, page, size, !order.ascending());
        return Paged.of(
                result.content().stream().map(EntryResponse::from).toList(),
                page,
                size,
                result.total());
    }

    private static UUID customerId(Jwt jwt) {
        String id = jwt.getClaimAsString("customer_id");
        if (id == null) {
            throw new DomainException(
                    ErrorCode.ACCESS_DENIED, "Ce compte n'a pas de fiche client.");
        }
        return UUID.fromString(id);
    }
}
