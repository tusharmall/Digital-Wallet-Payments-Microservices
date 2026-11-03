package com.example.wallet.repository;

import com.example.wallet.entity.TransactionType;
import com.example.wallet.entity.WalletTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {
    Optional<WalletTransaction> findByRequestedByUserIdAndIdempotencyKey(Long requestedByUserId, String idempotencyKey);

    Page<WalletTransaction> findBySourceWalletIdOrTargetWalletId(Long sourceWalletId, Long targetWalletId, Pageable pageable);

    @Query("select coalesce(sum(t.amount), 0) from WalletTransaction t " +
            "where t.sourceWalletId = :walletId and t.type = :type and t.status = 'COMPLETED' " +
            "and t.createdAt >= :start and t.createdAt < :end")
    BigDecimal sumAmountBySourceWalletAndTypeBetween(@Param("walletId") Long walletId,
                                                     @Param("type") TransactionType type,
                                                     @Param("start") Instant start,
                                                     @Param("end") Instant end);

    long countBySourceWalletIdAndTypeAndStatusAndCreatedAtGreaterThanEqual(Long sourceWalletId,
                                                                            TransactionType type,
                                                                            String status,
                                                                            Instant createdAt);

    @Query("select count(t) from WalletTransaction t where (t.sourceWalletId = :walletId or t.targetWalletId = :walletId) " +
            "and t.createdAt >= :start and t.createdAt < :end and t.createdAt < :cutoff")
    long countWalletTransactionsForPeriod(@Param("walletId") Long walletId,
                                          @Param("start") Instant start,
                                          @Param("end") Instant end,
                                          @Param("cutoff") Instant cutoff);
}
