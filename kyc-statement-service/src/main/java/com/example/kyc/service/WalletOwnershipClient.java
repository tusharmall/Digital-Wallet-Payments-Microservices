package com.example.kyc.service;

import com.example.kyc.dto.WalletOwnerResponse;
import com.example.kyc.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.Objects;

@Service
public class WalletOwnershipClient {
    private final RestClient restClient;
    private final String internalToken;

    public WalletOwnershipClient(@Value("${app.wallet-service.base-url:http://localhost:8081}") String baseUrl,
                                 @Value("${app.internal.service-token:local-internal-token-change-me}") String internalToken) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalToken = internalToken;
    }

    public void assertOwner(Long walletId, Long requestedUserId) {
        try {
            WalletOwnerResponse owner = restClient.get()
                    .uri("/internal/wallets/{walletId}/owner", walletId)
                    .header("X-Internal-Service-Token", internalToken)
                    .retrieve().body(WalletOwnerResponse.class);
            if (owner == null || !Objects.equals(owner.ownerUserId(), requestedUserId)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "WALLET_ACCESS_DENIED", "You do not own the requested wallet.");
            }
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found.");
            }
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "WALLET_SERVICE_UNAVAILABLE",
                    "Unable to verify wallet ownership. Please retry shortly.");
        } catch (RestClientException ex) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "WALLET_SERVICE_UNAVAILABLE",
                    "Unable to verify wallet ownership. Please retry shortly.");
        }
    }

    public long countTransactions(Long walletId, Instant start, Instant end, Instant cutoff) {
        try {
            Long count = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/wallets/{walletId}/transaction-count")
                            .queryParam("start", start.toString())
                            .queryParam("end", end.toString())
                            .queryParam("cutoff", cutoff.toString())
                            .build(walletId))
                    .header("X-Internal-Service-Token", internalToken)
                    .retrieve().body(Long.class);
            if (count == null) throw new IllegalStateException("Wallet service returned no transaction count.");
            return count;
        } catch (RestClientException ex) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "WALLET_SERVICE_UNAVAILABLE",
                    "Unable to obtain the transaction count from the wallet service.");
        }
    }
}
