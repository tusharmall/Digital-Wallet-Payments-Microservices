package com.example.wallet.dto;

import java.math.BigDecimal;

public record UserWalletResponse(Long userId, String fullName, String email,
                                 Long walletId, String currency, BigDecimal balance) { }
