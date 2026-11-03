package com.example.wallet.dto;

import java.math.BigDecimal;

public record WalletBalanceResponse(Long walletId, Long userId, String currency, BigDecimal balance) { }
