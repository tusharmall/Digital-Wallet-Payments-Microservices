package com.example.kyc.dto;

import com.example.kyc.entity.KycStatus;
import java.time.Instant;

public record KycDocumentResponse(String documentId, Long userId, String fileName,
                                  String contentType, KycStatus status, Instant createdAt,
                                  Instant updatedAt, String reviewNote) { }
