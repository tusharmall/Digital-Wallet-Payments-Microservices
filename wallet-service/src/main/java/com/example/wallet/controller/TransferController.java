package com.example.wallet.controller;

import com.example.wallet.dto.TransactionResponse;
import com.example.wallet.dto.TransferRequest;
import com.example.wallet.service.WalletApplicationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {
    private final WalletApplicationService walletService;

    public TransferController(WalletApplicationService walletService) { this.walletService = walletService; }

    @PostMapping
    public TransactionResponse transfer(@RequestHeader("X-User-Id") Long authenticatedUserId,
                                        @RequestHeader("Idempotency-Key") String idempotencyKey,
                                        @Valid @RequestBody TransferRequest request) {
        return walletService.transfer(request, authenticatedUserId, idempotencyKey);
    }
}
