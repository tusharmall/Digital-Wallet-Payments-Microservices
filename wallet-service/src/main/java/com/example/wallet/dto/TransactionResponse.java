package com.example.wallet.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(Long transactionId, String type,
                                  Long fromWalletId, Long toWalletId,
                                  BigDecimal amount, String currency,
                                  String status, Instant createdAt) { }
