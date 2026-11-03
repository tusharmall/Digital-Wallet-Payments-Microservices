package com.example.wallet.repository;

import com.example.wallet.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    long countByTransactionId(Long transactionId);
}
