package com.example.kyc.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** JSON schema must stay in sync with wallet-service's event record. */
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
