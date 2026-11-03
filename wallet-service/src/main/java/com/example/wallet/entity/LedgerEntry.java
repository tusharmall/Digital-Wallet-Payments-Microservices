package com.example.wallet.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "ledger_entries", indexes = @Index(name = "idx_ledger_transaction_id", columnList = "transaction_id"))
public class LedgerEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "wallet_id")
    private Long walletId;

    @Column(name = "account_code", nullable = false, length = 32)
    private String accountCode;

    @Column(name = "entry_type", nullable = false, length = 6)
    private String entryType;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }

    public LedgerEntry() { }
    public LedgerEntry(Long transactionId, Long walletId, String accountCode, String entryType, BigDecimal amount) {
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.accountCode = accountCode;
        this.entryType = entryType;
        this.amount = amount;
    }

    public Long getId() { return id; }
    public Long getTransactionId() { return transactionId; }
    public Long getWalletId() { return walletId; }
    public String getAccountCode() { return accountCode; }
    public String getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public Instant getCreatedAt() { return createdAt; }
}
