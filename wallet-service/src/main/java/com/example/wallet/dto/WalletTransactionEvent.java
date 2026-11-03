package com.example.wallet.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Event contract shared by JSON schema with KYC & Statement Service. */
public record WalletTransactionEvent(
        String eventId,
        Long transactionId,
        String transactionType,
        Long fromWalletId,
        Long toWalletId,
        Long fromUserId,
        Long toUserId,
        String fromEmail,
        String toEmail,
        BigDecimal amount,
        String currency,
        String status,
        Instant occurredAt
) { }
