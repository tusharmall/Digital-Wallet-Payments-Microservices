package com.example.wallet.controller;

import com.example.wallet.dto.AddMoneyRequest;
import com.example.wallet.dto.TransactionResponse;
import com.example.wallet.dto.WalletBalanceResponse;
import com.example.wallet.service.WalletApplicationService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wallets")
public class WalletController {
    private final WalletApplicationService walletService;

    public WalletController(WalletApplicationService walletService) { this.walletService = walletService; }

    @GetMapping("/{walletId}/balance")
    public WalletBalanceResponse balance(@PathVariable Long walletId,
                                         @RequestHeader("X-User-Id") Long authenticatedUserId) {
        return walletService.getBalance(walletId, authenticatedUserId);
    }

    @PostMapping("/{walletId}/add-money")
    public TransactionResponse addMoney(@PathVariable Long walletId,
                                        @RequestHeader("X-User-Id") Long authenticatedUserId,
                                        @RequestHeader("Idempotency-Key") String idempotencyKey,
                                        @Valid @RequestBody AddMoneyRequest request) {
        return walletService.addMoney(walletId, authenticatedUserId, request.amount(), idempotencyKey);
    }

    @GetMapping("/{walletId}/transactions")
    public Page<TransactionResponse> history(@PathVariable Long walletId,
                                             @RequestHeader("X-User-Id") Long authenticatedUserId,
                                             @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return walletService.getHistory(walletId, authenticatedUserId, pageable);
    }
}
