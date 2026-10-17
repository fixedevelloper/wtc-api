package com.wtc.migration;

import com.wtc.shared.security.Tokens;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Recharges historiques, jetons web, journal de connexions et journal d'actions. */
final class MiscStage {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EtlContext ctx;
    private final com.wtc.wallet.application.WalletService wallets;

    MiscStage(EtlContext ctx, com.wtc.wallet.application.WalletService wallets) {
        this.ctx = ctx;
        this.wallets = wallets;
    }

    void run() {
        ctx.report.stage("misc");
        topUps();
        webTokens();
        loginAudit();
        actionLog();
    }

    /**
     * Historique seul : aucune écriture au grand livre (le solde d'ouverture couvre ce qui a déjà
     * été crédité).
     */
    private void topUps() {
        for (Row p : ctx.rows("SELECT * FROM prefunding ORDER BY id")) {
            EtlContext.Ref holder =
                    p.id("customer_id") == null
                            ? null
                            : ctx.holderByLegacyId.get(p.id("customer_id"));
            String iso =
                    p.id("country_id") == null
                            ? null
                            : ctx.countryByLegacyId.get(p.id("country_id"));
            if (holder == null
                    || p.decimal("amount") == null
                    || p.decimal("amount").signum() <= 0) {
                ctx.report.error(
                        "prefunding",
                        p.idOrZero(),
                        "Recharge sans client migré ou sans montant : non migrée",
                        p.forReport());
                ctx.report.count("topups.rejected");
                continue;
            }
            String currency =
                    iso == null ? TransferStage.SOURCE_CURRENCY : ctx.currencyByCountry.get(iso);
            var wallet = wallets.ensureWallet(holder.publicId(), currency);
            String legacyStatus =
                    p.str("status") == null ? "" : p.str("status").toUpperCase(Locale.ROOT);
            String status =
                    switch (legacyStatus) {
                        case "PAID", "SUCCESS", "COMPLETED", "EFFECTUE" -> "COMPLETED";
                        case "FAILED", "CANCELED", "CANCELLED", "ECHOUE" -> "FAILED";
                        default -> "PENDING";
                    };
            if ("PENDING".equals(status)) {
                ctx.report.warn(
                        "prefunding",
                        p.idOrZero(),
                        "Recharge en attente reprise telle quelle : à traiter par un administrateur"
                                + " (ne pas approuver sans vérifier le paiement)",
                        p.forReport());
            }
            Instant at =
                    p.instant("dateprefund") == null
                            ? ctx.clock.instant()
                            : p.instant("dateprefund");
            boolean ok =
                    ctx.attempt(
                            () ->
                                    ctx.target
                                            .sql(
                                                    """
                                                    INSERT INTO top_ups (reference, customer_id, wallet_id, method, amount, currency_code, status, failure_reason, legacy_id, created_at, updated_at)
                                                    VALUES (:ref, :customer, :wallet, 'LEGACY', :amount, :cur, :status, :reason, :legacy, :at, :at)
                                                    ON CONFLICT (legacy_id) DO NOTHING
                                                    """)
                                            .param("ref", "LEGACY-TOPUP-" + p.idOrZero())
                                            .param("customer", holder.id())
                                            .param("wallet", wallet.id())
                                            .param(
                                                    "amount",
                                                    p.decimal("amount")
                                                            .setScale(
                                                                    4,
                                                                    java.math.RoundingMode.HALF_UP))
                                            .param("cur", currency)
                                            .param("status", status)
                                            .param(
                                                    "reason",
                                                    "Historique migré (mode : "
                                                            + p.str("mode")
                                                            + ", statut d'origine : "
                                                            + p.str("status")
                                                            + ")")
                                            .param("legacy", p.idOrZero())
                                            .param("at", Timestamp.from(at))
                                            .update());
            if (ok) {
                ctx.report.count("topups.migrated");
            } else {
                ctx.report.error(
                        "prefunding",
                        p.idOrZero(),
                        "Recharge refusée par le schéma : " + ctx.lastError,
                        p.forReport());
                ctx.report.count("topups.rejected");
            }
        }
    }

    /**
     * Les jetons non expirés restent valables (empreinte du jeton d'origine) pour ne pas casser les
     * liens WhatsApp en cours.
     */
    private void webTokens() {
        for (Row w : ctx.rows("SELECT * FROM web_token ORDER BY id")) {
            String iso =
                    w.id("country_id") == null
                            ? null
                            : ctx.countryByLegacyId.get(w.id("country_id"));
            if (w.str("token") == null
                    || iso == null
                    || w.decimal("amount") == null
                    || w.decimal("amount").signum() <= 0
                    || w.instant("expires_at") == null) {
                ctx.report.error(
                        "web_token",
                        w.idOrZero(),
                        "Jeton web inexploitable : non migré",
                        w.forReport());
                ctx.report.count("web_tokens.rejected");
                continue;
            }
            boolean used = w.bool("used") || "USED".equalsIgnoreCase(w.str("status"));
            Instant usedAt =
                    used
                            ? (w.instant("used_at") != null
                                    ? w.instant("used_at")
                                    : w.instant("created_at"))
                            : null;
            String status =
                    used
                            ? "USED"
                            : w.instant("expires_at").isBefore(ctx.clock.instant())
                                    ? "EXPIRED"
                                    : "PENDING";
            boolean ok =
                    ctx.attempt(
                            () ->
                                    ctx.target
                                            .sql(
                                                    """
                                                    INSERT INTO web_tokens (token_hash, amount, country_code, whatsapp_id, status, expires_at, used_at, created_at, legacy_id)
                                                    VALUES (:hash, :amount, :country, :wa, :status, :exp, :usedAt, :created, :legacy)
                                                    ON CONFLICT (legacy_id) DO NOTHING
                                                    """)
                                            .param("hash", Tokens.sha256(w.str("token")))
                                            .param(
                                                    "amount",
                                                    w.decimal("amount")
                                                            .setScale(
                                                                    4,
                                                                    java.math.RoundingMode.HALF_UP))
                                            .param("country", iso)
                                            .param("wa", w.str("wa_id"))
                                            .param("status", status)
                                            .param("exp", Timestamp.from(w.instant("expires_at")))
                                            .param(
                                                    "usedAt",
                                                    usedAt == null ? null : Timestamp.from(usedAt))
                                            .param(
                                                    "created",
                                                    Timestamp.from(
                                                            w.instant("created_at") == null
                                                                    ? ctx.clock.instant()
                                                                    : w.instant("created_at")))
                                            .param("legacy", w.idOrZero())
                                            .update());
            ctx.report.count(ok ? "web_tokens.migrated" : "web_tokens.rejected");
            if (!ok) {
                ctx.report.error(
                        "web_token",
                        w.idOrZero(),
                        "Jeton refusé par le schéma : " + ctx.lastError,
                        w.forReport());
            }
        }
    }

    private void loginAudit() {
        for (Row l : ctx.rows("SELECT * FROM login_audit ORDER BY id")) {
            EtlContext.Ref user =
                    l.id("user_id") == null ? null : ctx.userByLegacyId.get(l.id("user_id"));
            String email =
                    l.str("email") == null ? "(inconnu)" : l.str("email").toLowerCase(Locale.ROOT);
            String ip =
                    l.str("ip_address") != null && l.str("ip_address").matches("[0-9a-fA-F:.]+")
                            ? l.str("ip_address")
                            : null;
            Instant at =
                    l.instant("created_at") == null ? ctx.clock.instant() : l.instant("created_at");
            ctx.target
                    .sql(
                            """
                            INSERT INTO login_audit (user_id, email_attempted, outcome, ip_address, occurred_at)
                            VALUES (:user, :email, :outcome, CAST(:ip AS inet), :at)
                            """)
                    .param("user", user == null ? null : user.id())
                    .param("email", email)
                    .param("outcome", l.bool("success") ? "SUCCESS" : "BAD_CREDENTIALS")
                    .param("ip", ip)
                    .param("at", Timestamp.from(at))
                    .update();
            ctx.report.count("login_audit.migrated");
        }
    }

    private void actionLog() {
        for (Row a : ctx.rows("SELECT * FROM user_action_log ORDER BY id")) {
            EtlContext.Ref user =
                    a.id("user_id") == null ? null : ctx.userByLegacyId.get(a.id("user_id"));
            Instant at =
                    a.instant("created_at") == null ? ctx.clock.instant() : a.instant("created_at");
            String ip =
                    a.str("ip_address") != null && a.str("ip_address").matches("[0-9a-fA-F:.]+")
                            ? a.str("ip_address")
                            : null;
            ctx.target
                    .sql(
                            """
                            INSERT INTO audit_log (occurred_at, actor_user_id, actor_type, action, entity_type, entity_id, after_state, ip_address)
                            VALUES (:at, :user, 'USER', :action, 'LEGACY_ACTION', :entity, CAST(:state AS jsonb), CAST(:ip AS inet))
                            """)
                    .param("at", Timestamp.from(at))
                    .param("user", user == null ? null : user.id())
                    .param("action", a.str("action") == null ? "LEGACY" : a.str("action"))
                    .param("entity", "user_action_log:" + a.idOrZero())
                    .param(
                            "state",
                            JSON.writeValueAsString(
                                    Map.of(
                                            "details",
                                            a.str("details") == null ? "" : a.str("details"))))
                    .param("ip", ip)
                    .update();
            ctx.report.count("audit_log.migrated");
        }
    }
}
