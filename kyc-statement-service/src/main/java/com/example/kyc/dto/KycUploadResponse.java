package com.example.kyc.dto;

import com.example.kyc.entity.KycStatus;
import java.time.Instant;
import java.util.Map;

public record KycUploadResponse(String documentId, String uploadUrl, String objectKey,
                                KycStatus status, Instant expiresAt, Map<String, String> requiredHeaders) { }
