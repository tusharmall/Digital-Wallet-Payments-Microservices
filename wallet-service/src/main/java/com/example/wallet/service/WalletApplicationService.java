package com.example.wallet.service;

import com.example.wallet.dto.*;
import com.example.wallet.entity.*;
import com.example.wallet.exception.BusinessException;
import com.example.wallet.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;

@Service
public class WalletApplicationService {
    private final UserAccountRepository userRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final OutboxEventRepository outboxRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Value("${wallet.transfer.daily-limit:100000.00}")
    private BigDecimal dailyTransferLimit;

    @Value("${wallet.transfer.max-single-amount:50000.00}")
    private BigDecimal maxSingleTransferAmount;

    @Value("${wallet.fraud.max-transfers-per-minute:10}")
    private long maxSuccessfulTransfersPerMinute;

    @Value("${wallet.rate-limit.transfers-per-minute:30}")
    private long maxTransferRequestsPerMinute;

    public WalletApplicationService(UserAccountRepository userRepository,
                                    WalletRepository walletRepository,
                                    WalletTransactionRepository transactionRepository,
                                    LedgerEntryRepository ledgerRepository,
                                    OutboxEventRepository outboxRepository,
                                    StringRedisTemplate redisTemplate,
                                    ObjectMapper objectMapper,
                                    PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerRepository = ledgerRepository;
        this.outboxRepository = outboxRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public UserWalletResponse createUser(CreateUserRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "A user with this email already exists.");
        }
        UserAccount user = new UserAccount();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        userRepository.saveAndFlush(user);

        Wallet wallet = new Wallet();
        wallet.setUser(user);
        wallet.setBalance(BigDecimal.ZERO.setScale(2));
        wallet.setCurrency("INR");
        walletRepository.saveAndFlush(wallet);
        return new UserWalletResponse(user.getId(), user.getFullName(), user.getEmail(),
                wallet.getId(), wallet.getCurrency(), wallet.getBalance());
    }

    public WalletBalanceResponse getBalance(Long walletId, Long authenticatedUserId) {
        assertWalletOwner(walletId, authenticatedUserId);
        BigDecimal balance = walletRepository.findBalanceByWalletId(walletId)
                .orElseThrow(() -> notFound("WALLET_NOT_FOUND", "Wallet not found."));
        return new WalletBalanceResponse(walletId, authenticatedUserId, "INR", balance);
    }

    public Page<TransactionResponse> getHistory(Long walletId, Long authenticatedUserId, Pageable pageable) {
        assertWalletOwner(walletId, authenticatedUserId);
        if (!walletRepository.existsById(walletId)) {
            throw notFound("WALLET_NOT_FOUND", "Wallet not found.");
        }
        return transactionRepository.findBySourceWalletIdOrTargetWalletId(walletId, walletId, pageable)
                .map(this::toResponse);
    }

    public TransactionResponse addMoney(Long walletId, Long authenticatedUserId, BigDecimal rawAmount, String rawKey) {
        assertWalletOwner(walletId, authenticatedUserId);
        BigDecimal amount = normalizeAmount(rawAmount);
        if (amount.compareTo(new BigDecimal("1000000.00")) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "AMOUNT_TOO_LARGE", "The simulated add-money limit is INR 1,000,000 per request.");
        }
        String key = validateIdempotencyKey(rawKey);
        String fingerprint = sha256("ADD_MONEY|" + authenticatedUserId + "|" + walletId + "|" + amount.toPlainString());

        return runIdempotently(authenticatedUserId, key, fingerprint, () -> transactionTemplate.execute(status -> {
            Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                    .orElseThrow(() -> notFound("WALLET_NOT_FOUND", "Wallet not found."));
            if (!Objects.equals(wallet.getUser().getId(), authenticatedUserId)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "WALLET_ACCESS_DENIED", "You do not own this wallet.");
            }
            wallet.setBalance(wallet.getBalance().add(amount));
            walletRepository.save(wallet);

            WalletTransaction transaction = new WalletTransaction();
            transaction.setType(TransactionType.ADD_MONEY);
            transaction.setSourceWalletId(null);
            transaction.setTargetWalletId(wallet.getId());
            transaction.setSourceUserId(null);
            transaction.setTargetUserId(wallet.getUser().getId());
            transaction.setRequestedByUserId(authenticatedUserId);
            transaction.setAmount(amount);
            transaction.setCurrency(wallet.getCurrency());
            transaction.setStatus("COMPLETED");
            transaction.setIdempotencyKey(key);
            transaction.setRequestFingerprint(fingerprint);
            transactionRepository.saveAndFlush(transaction);

            ledgerRepository.saveAll(List.of(
                    new LedgerEntry(transaction.getId(), null, "SYSTEM_CASH", "DEBIT", amount),
                    new LedgerEntry(transaction.getId(), wallet.getId(), "WALLET", "CREDIT", amount)
            ));
            appendOutboxEvent(transaction, null, wallet);
            return toResponse(transaction);
        }));
    }

    public TransactionResponse transfer(TransferRequest request, Long authenticatedUserId, String rawKey) {
        if (Objects.equals(request.fromWalletId(), request.toWalletId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SELF_TRANSFER_NOT_ALLOWED", "Source and destination wallets must be different.");
        }
        assertWalletOwner(request.fromWalletId(), authenticatedUserId);
        BigDecimal amount = normalizeAmount(request.amount());
        if (amount.compareTo(maxSingleTransferAmount) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SINGLE_TRANSFER_LIMIT_EXCEEDED",
                    "The maximum amount per transfer is INR " + maxSingleTransferAmount.toPlainString() + ".");
        }
        String key = validateIdempotencyKey(rawKey);
        String fingerprint = sha256("TRANSFER|" + authenticatedUserId + "|" + request.fromWalletId() + "|" + request.toWalletId() + "|" + amount.toPlainString());

        return runIdempotently(authenticatedUserId, key, fingerprint, () -> transactionTemplate.execute(status -> {
            // Always acquire wallet row locks in ascending ID order. Every transfer uses this same order.
            List<Long> lockOrder = new ArrayList<>(List.of(request.fromWalletId(), request.toWalletId()));
            lockOrder.sort(Comparator.naturalOrder());
            Map<Long, Wallet> lockedWallets = new HashMap<>();
            for (Long id : lockOrder) {
                Wallet wallet = walletRepository.findByIdForUpdate(id)
                        .orElseThrow(() -> notFound("WALLET_NOT_FOUND", "Wallet " + id + " was not found."));
                lockedWallets.put(id, wallet);
            }
            Wallet source = lockedWallets.get(request.fromWalletId());
            Wallet target = lockedWallets.get(request.toWalletId());
            if (!Objects.equals(source.getUser().getId(), authenticatedUserId)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "WALLET_ACCESS_DENIED", "You do not own the source wallet.");
            }
            if (!Objects.equals(source.getCurrency(), target.getCurrency())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "CURRENCY_MISMATCH", "Transfers between different currencies are not supported.");
            }

            enforceRedisRateLimit(source.getId());
            Instant now = Instant.now();
            Instant dayStart = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant dayEnd = LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            BigDecimal alreadyTransferred = transactionRepository.sumAmountBySourceWalletAndTypeBetween(
                    source.getId(), TransactionType.TRANSFER, dayStart, dayEnd);
            if (alreadyTransferred.add(amount).compareTo(dailyTransferLimit) > 0) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "DAILY_TRANSFER_LIMIT_EXCEEDED",
                        "The daily transfer limit of INR " + dailyTransferLimit.toPlainString() + " would be exceeded.");
            }
            Instant minuteAgo = now.minus(Duration.ofMinutes(1));
            long recentCount = transactionRepository.countBySourceWalletIdAndTypeAndStatusAndCreatedAtGreaterThanEqual(
                    source.getId(), TransactionType.TRANSFER, "COMPLETED", minuteAgo);
            if (recentCount >= maxSuccessfulTransfersPerMinute) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "FRAUD_VELOCITY_RULE",
                        "Transfer declined by the rapid-transfer safety rule. Try again later.");
            }
            if (source.getBalance().compareTo(amount) < 0) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "The source wallet has insufficient funds.");
            }

            source.setBalance(source.getBalance().subtract(amount));
            target.setBalance(target.getBalance().add(amount));
            walletRepository.save(source);
            walletRepository.save(target);

            WalletTransaction transaction = new WalletTransaction();
            transaction.setType(TransactionType.TRANSFER);
            transaction.setSourceWalletId(source.getId());
            transaction.setTargetWalletId(target.getId());
            transaction.setSourceUserId(source.getUser().getId());
            transaction.setTargetUserId(target.getUser().getId());
            transaction.setRequestedByUserId(authenticatedUserId);
            transaction.setAmount(amount);
            transaction.setCurrency(source.getCurrency());
            transaction.setStatus("COMPLETED");
            transaction.setIdempotencyKey(key);
            transaction.setRequestFingerprint(fingerprint);
            transactionRepository.saveAndFlush(transaction);

            ledgerRepository.saveAll(List.of(
                    new LedgerEntry(transaction.getId(), source.getId(), "WALLET", "DEBIT", amount),
                    new LedgerEntry(transaction.getId(), target.getId(), "WALLET", "CREDIT", amount)
            ));
            appendOutboxEvent(transaction, source, target);
            return toResponse(transaction);
        }));
    }

    public Long getOwnerId(Long walletId) {
        return walletRepository.findOwnerIdByWalletId(walletId)
                .orElseThrow(() -> notFound("WALLET_NOT_FOUND", "Wallet not found."));
    }

    public long countWalletTransactionsForPeriod(Long walletId, Instant start, Instant end, Instant cutoff) {
        if (start == null || end == null || cutoff == null || !start.isBefore(end)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATEMENT_RANGE", "A valid statement date range is required.");
        }
        if (!walletRepository.existsById(walletId)) {
            throw notFound("WALLET_NOT_FOUND", "Wallet not found.");
        }
        return transactionRepository.countWalletTransactionsForPeriod(walletId, start, end, cutoff);
    }

    private TransactionResponse runIdempotently(Long userId, String key, String fingerprint,
                                                Supplier<TransactionResponse> action) {
        TransactionResponse cached = readCachedResponse(userId, key, fingerprint);
        if (cached != null) return cached;

        Optional<WalletTransaction> existing = transactionRepository.findByRequestedByUserIdAndIdempotencyKey(userId, key);
        if (existing.isPresent()) return replayOrConflict(userId, key, fingerprint, existing.get());

        try {
            TransactionResponse response = action.get();
            cacheResponse(userId, key, fingerprint, response);
            return response;
        } catch (RuntimeException ex) {
            // A unique database constraint is the concurrency-safe final guard if two identical keys race.
            Optional<WalletTransaction> createdByConcurrentRequest = transactionRepository
                    .findByRequestedByUserIdAndIdempotencyKey(userId, key);
            if (createdByConcurrentRequest.isPresent()) {
                return replayOrConflict(userId, key, fingerprint, createdByConcurrentRequest.get());
            }
            throw ex;
        }
    }

    private TransactionResponse replayOrConflict(Long userId, String key, String fingerprint, WalletTransaction existing) {
        if (!Objects.equals(existing.getRequestFingerprint(), fingerprint)) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used with a different request payload.");
        }
        TransactionResponse response = toResponse(existing);
        cacheResponse(userId, key, fingerprint, response);
        return response;
    }

    private TransactionResponse readCachedResponse(Long userId, String key, String fingerprint) {
        try {
            String payload = redisTemplate.opsForValue().get(cacheKey(userId, key));
            if (payload == null) return null;
            CachedIdempotencyResult cached = objectMapper.readValue(payload, CachedIdempotencyResult.class);
            if (!Objects.equals(cached.fingerprint(), fingerprint)) {
                throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used with a different request payload.");
            }
            return cached.response();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ignored) {
            // Redis is an optimization only. The relational unique constraint remains authoritative.
            return null;
        }
    }

    private void cacheResponse(Long userId, String key, String fingerprint, TransactionResponse response) {
        try {
            String payload = objectMapper.writeValueAsString(new CachedIdempotencyResult(fingerprint, response));
            redisTemplate.opsForValue().set(cacheKey(userId, key), payload, Duration.ofHours(24));
        } catch (Exception ignored) {
            // Database persistence provides correctness if a cache write fails.
        }
    }

    private void enforceRedisRateLimit(Long walletId) {
        Instant now = Instant.now();
        long minuteBucket = now.getEpochSecond() / 60;
        String key = "wallet:transfer-rate:" + walletId + ":" + minuteBucket;
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) redisTemplate.expire(key, Duration.ofSeconds(75));
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMITER_UNAVAILABLE",
                    "Transfer rate limiting is temporarily unavailable. Please retry shortly.");
        }
        if (count != null && count > maxTransferRequestsPerMinute) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
                    "Too many transfer requests. Please retry in the next minute.");
        }
    }

    private void appendOutboxEvent(WalletTransaction transaction, Wallet source, Wallet target) {
        String eventId = UUID.randomUUID().toString();
        String fromEmail = source == null ? null : source.getUser().getEmail();
        String toEmail = target == null ? null : target.getUser().getEmail();
        WalletTransactionEvent event = new WalletTransactionEvent(
                eventId,
                transaction.getId(),
                transaction.getType().name(),
                source == null ? null : source.getId(),
                target == null ? null : target.getId(),
                source == null ? null : source.getUser().getId(),
                target == null ? null : target.getUser().getId(),
                fromEmail,
                toEmail,
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                transaction.getCreatedAt()
        );
        try {
            outboxRepository.save(new OutboxEvent(eventId, "WALLET_TRANSACTION",
                    String.valueOf(transaction.getId()), "WalletTransactionCompleted", objectMapper.writeValueAsString(event)));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize the transaction event for the outbox.", ex);
        }
    }

    private void assertWalletOwner(Long walletId, Long authenticatedUserId) {
        Long ownerId = walletRepository.findOwnerIdByWalletId(walletId)
                .orElseThrow(() -> notFound("WALLET_NOT_FOUND", "Wallet not found."));
        if (!Objects.equals(ownerId, authenticatedUserId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "WALLET_ACCESS_DENIED", "You do not own this wallet.");
        }
    }

    private BigDecimal normalizeAmount(BigDecimal rawAmount) {
        if (rawAmount == null || rawAmount.signum() <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Amount must be greater than zero.");
        }
        if (rawAmount.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT_SCALE", "Amount must have at most two decimal places.");
        }
        return rawAmount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private String validateIdempotencyKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank() || rawKey.length() > 128) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key is required and must contain between 1 and 128 characters.");
        }
        return rawKey.trim();
    }

    private String cacheKey(Long userId, String key) {
        return "wallet:idempotency:" + userId + ":" + sha256(key);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", ex);
        }
    }

    private TransactionResponse toResponse(WalletTransaction transaction) {
        return new TransactionResponse(transaction.getId(), transaction.getType().name(),
                transaction.getSourceWalletId(), transaction.getTargetWalletId(),
                transaction.getAmount(), transaction.getCurrency(), transaction.getStatus(), transaction.getCreatedAt());
    }

    private BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }
}
