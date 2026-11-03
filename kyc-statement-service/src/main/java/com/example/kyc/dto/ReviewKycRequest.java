package com.example.kyc.dto;

import com.example.kyc.entity.KycStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewKycRequest(
        @NotNull KycStatus status,
        @Size(max = 1000) String note
) { }
