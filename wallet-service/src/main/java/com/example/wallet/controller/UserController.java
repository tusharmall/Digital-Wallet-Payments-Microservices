package com.example.wallet.controller;

import com.example.wallet.dto.CreateUserRequest;
import com.example.wallet.dto.UserWalletResponse;
import com.example.wallet.service.WalletApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final WalletApplicationService walletService;

    public UserController(WalletApplicationService walletService) { this.walletService = walletService; }

    /** Demo onboarding endpoint: production deployments should apply their verified signup/KYC flow here. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserWalletResponse createUser(@Valid @RequestBody CreateUserRequest request) {
        return walletService.createUser(request);
    }
}
