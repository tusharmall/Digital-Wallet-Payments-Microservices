package com.example.wallet.controller;

import com.example.wallet.dto.WalletOwnerResponse;
import com.example.wallet.exception.BusinessException;
import com.example.wallet.service.WalletApplicationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/internal/wallets")
public class InternalWalletController {
    private final WalletApplicationService walletService;
    private final String internalToken;

    public InternalWalletController(WalletApplicationService walletService,
                                    @Value("${app.internal.service-token:local-internal-token-change-me}") String internalToken) {
        this.walletService = walletService;
        this.internalToken = internalToken;
    }

    /** Must remain private-network only; the token must be injected by the trusted service, not the public gateway. */
    @GetMapping("/{walletId}/owner")
    public WalletOwnerResponse owner(@PathVariable Long walletId,
                                     @RequestHeader(value = "X-Internal-Service-Token", required = false) String token) {
        requireValidToken(token);
        return new WalletOwnerResponse(walletId, walletService.getOwnerId(walletId));
    }

    @GetMapping("/{walletId}/transaction-count")
    public long transactionCount(@PathVariable Long walletId,
                                 @RequestHeader(value = "X-Internal-Service-Token", required = false) String token,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant cutoff) {
        requireValidToken(token);
        return walletService.countWalletTransactionsForPeriod(walletId, start, end, cutoff);
    }

    private void requireValidToken(String token) {
        if (token == null || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "INTERNAL_ACCESS_DENIED", "Internal service authentication failed.");
        }
    }
}
