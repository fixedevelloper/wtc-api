package com.wtc.wallet.application;

import java.math.BigDecimal;
import java.util.UUID;

public record WalletView(UUID id, String currency, BigDecimal balance, String status) {}
