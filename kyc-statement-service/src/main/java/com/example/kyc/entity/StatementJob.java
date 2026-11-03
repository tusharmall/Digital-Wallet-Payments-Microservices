package com.example.kyc.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "statement_jobs", indexes = @Index(name = "idx_statement_user_created", columnList = "user_id,created_at"))
public class StatementJob {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "statement_month", nullable = false, length = 7)
    private String statementMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StatementStatus status = StatementStatus.PENDING;

    @Column(name = "cutoff_at", nullable = false)
    private Instant cutoffAt;

    @Column(name = "expected_transaction_count", nullable = false)
    private long expectedTransactionCount;

    @Column(name = "object_key", length = 700)
    private String objectKey;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); if (status == null) status = StatementStatus.PENDING; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getWalletId() { return walletId; }
    public void setWalletId(Long walletId) { this.walletId = walletId; }
    public String getStatementMonth() { return statementMonth; }
    public void setStatementMonth(String statementMonth) { this.statementMonth = statementMonth; }
    public StatementStatus getStatus() { return status; }
    public void setStatus(StatementStatus status) { this.status = status; }
    public Instant getCutoffAt() { return cutoffAt; }
    public void setCutoffAt(Instant cutoffAt) { this.cutoffAt = cutoffAt; }
    public long getExpectedTransactionCount() { return expectedTransactionCount; }
    public void setExpectedTransactionCount(long expectedTransactionCount) { this.expectedTransactionCount = expectedTransactionCount; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
