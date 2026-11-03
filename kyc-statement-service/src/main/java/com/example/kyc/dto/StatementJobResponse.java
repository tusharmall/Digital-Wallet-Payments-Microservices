package com.example.kyc.dto;

import com.example.kyc.entity.StatementStatus;
import java.time.Instant;

public record StatementJobResponse(String jobId, Long walletId, String month,
                                   StatementStatus status, Instant createdAt,
                                   Instant completedAt, String downloadUrl,
                                   Instant downloadUrlExpiresAt, String errorMessage) { }
