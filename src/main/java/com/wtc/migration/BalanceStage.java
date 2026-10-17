package com.wtc.migration;

import com.wtc.wallet.application.AccountRef;
import com.wtc.wallet.application.LedgerKind;
import com.wtc.wallet.application.LedgerService;
import com.wtc.wallet.application.PostingLeg;
import com.wtc.wallet.application.PostingRequest;
import com.wtc.wallet.application.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Soldes d'ouverture : un portefeuille par client et par devise, crédité par une écriture {@code
 * OPENING_BALANCE} (contrepartie {@code PLATFORM_OPENING_BALANCE}) passée par le service du grand
 * livre, donc avec ses garanties (équilibre, idempotence par référence). Un solde négatif n'est
 * jamais repris : le portefeuille reste à zéro et le cas est listé pour décision.
 */
final class BalanceStage {

    private final EtlContext ctx;
    private final WalletService wallets;
    private final LedgerService ledger;
    private final Map<String, BigDecimal> perWallet = new HashMap<>();

    BalanceStage(EtlContext ctx, WalletService wallets, LedgerService ledger) {
        this.ctx = ctx;
        this.wallets = wallets;
        this.ledger = ledger;
    }

    void run() {
        ctx.report.stage("balances");
        for (Row c :
                ctx.rows(
                        "SELECT id, balance FROM customer WHERE balance IS NOT NULL AND balance <>"
                                + " 0 ORDER BY id")) {
            open(
                    c,
                    c.id("id"),
                    TransferStage.SOURCE_CURRENCY,
                    c.decimal("balance"),
                    "customer.balance");
        }
        for (Row b :
                ctx.rows(
                        "SELECT id, customer_id, country_id, solde FROM balance_customer WHERE"
                                + " solde IS NOT NULL AND solde <> 0 ORDER BY id")) {
            String iso =
                    b.id("country_id") == null
                            ? null
                            : ctx.countryByLegacyId.get(b.id("country_id"));
            if (iso == null || b.id("customer_id") == null) {
                ctx.report.error(
                        "balance_customer",
                        b.idOrZero(),
                        "Solde sans client ou sans pays migré : non repris",
                        b.forReport());
                ctx.report.count("balances.rejected");
                continue;
            }
            open(
                    b,
                    b.id("customer_id"),
                    ctx.currencyByCountry.get(iso),
                    b.decimal("solde"),
                    "balance_customer:" + b.idOrZero());
        }
    }

    private void open(
            Row row, Long customerLegacy, String currency, BigDecimal raw, String source) {
        EtlContext.Ref holder =
                customerLegacy == null ? null : ctx.holderByLegacyId.get(customerLegacy);
        if (holder == null) {
            ctx.report.error(
                    "balance",
                    row.idOrZero(),
                    "Solde d'un client non migré ("
                            + source
                            + ", "
                            + raw
                            + " "
                            + currency
                            + ") : non repris",
                    row.forReport());
            ctx.report.count("balances.rejected");
            return;
        }
        int digits =
                Math.max(java.util.Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        BigDecimal amount = raw.setScale(digits, RoundingMode.HALF_UP);
        if (amount.compareTo(raw) != 0) {
            ctx.report.warn(
                    "balance",
                    row.idOrZero(),
                    "Solde arrondi à la précision de la devise "
                            + currency
                            + " : "
                            + raw.toPlainString()
                            + " → "
                            + amount.toPlainString()
                            + " ("
                            + source
                            + ")",
                    row.forReport());
        }
        wallets.ensureWallet(holder.publicId(), currency);
        if (amount.signum() < 0) {
            ctx.report.error(
                    "balance",
                    row.idOrZero(),
                    "Solde NÉGATIF ("
                            + amount
                            + " "
                            + currency
                            + ", "
                            + source
                            + ") : portefeuille à zéro, écart à traiter par l'exploitation",
                    row.forReport());
            ctx.report.count("balances.negative");
            return;
        }
        String walletKey = holder.id() + ":" + currency;
        if (perWallet.containsKey(walletKey)) {
            ctx.report.warn(
                    "balance",
                    row.idOrZero(),
                    "Le client a déjà un solde en "
                            + currency
                            + " : les deux soldes sont additionnés ("
                            + source
                            + ")",
                    row.forReport());
        }
        var walletRow = wallets.ensureWallet(holder.publicId(), currency);
        ledger.post(
                new PostingRequest(
                        "opening:" + customerLegacy + ":" + currency + ":" + source,
                        LedgerKind.OPENING_BALANCE,
                        "Solde d'ouverture repris de l'ancien système (" + source + ")",
                        List.of(
                                PostingLeg.debit(
                                        AccountRef.platform("PLATFORM_OPENING_BALANCE"),
                                        amount,
                                        currency),
                                PostingLeg.credit(
                                        AccountRef.wallet(walletRow.id()), amount, currency)),
                        ctx.migrationUserPublicId,
                        "CUSTOMER",
                        holder.id()));
        perWallet.merge(walletKey, amount, BigDecimal::add);
        ctx.report.count("balances.opened");
    }

    /** Total repris par devise (contrôle). */
    Map<String, BigDecimal> totalsByCurrency() {
        Map<String, BigDecimal> totals = new HashMap<>();
        perWallet.forEach(
                (k, v) -> totals.merge(k.substring(k.indexOf(':') + 1), v, BigDecimal::add));
        return totals;
    }
}
