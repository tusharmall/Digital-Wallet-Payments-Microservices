package com.example.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record TransferRequest(
        @NotNull @Positive Long fromWalletId,
        @NotNull @Positive Long toWalletId,
        @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal amount
) { }
