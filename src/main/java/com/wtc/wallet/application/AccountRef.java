package com.wtc.wallet.application;

/**
 * Compte du grand livre visé par une écriture : le portefeuille d'un client (par son identifiant
 * interne) ou un compte de la plateforme (par son code de base, par exemple {@code
 * PLATFORM_FEE_REVENUE}, décliné par devise).
 */
public sealed interface AccountRef {

    record Wallet(long walletId) implements AccountRef {}

    record Platform(String baseCode) implements AccountRef {}

    static AccountRef wallet(long walletId) {
        return new Wallet(walletId);
    }

    static AccountRef platform(String baseCode) {
        return new Platform(baseCode);
    }
}
