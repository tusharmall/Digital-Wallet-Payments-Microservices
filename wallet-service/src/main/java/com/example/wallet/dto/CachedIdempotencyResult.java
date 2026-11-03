package com.example.wallet.dto;

public record CachedIdempotencyResult(String fingerprint, TransactionResponse response) { }
