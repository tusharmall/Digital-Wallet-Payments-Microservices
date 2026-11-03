package com.example.kyc.repository;

import com.example.kyc.entity.TransactionEventProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface TransactionEventProjectionRepository extends JpaRepository<TransactionEventProjection, String> {
    @Query("select e from TransactionEventProjection e " +
            "where (e.fromWalletId = :walletId or e.toWalletId = :walletId) " +
            "and e.occurredAt >= :start and e.occurredAt < :end and e.occurredAt < :cutoff order by e.occurredAt asc")
    List<TransactionEventProjection> findWalletEventsForPeriod(@Param("walletId") Long walletId,
                                                                 @Param("start") Instant start,
                                                                 @Param("end") Instant end,
                                                                 @Param("cutoff") Instant cutoff);

    @Query("select count(e) from TransactionEventProjection e where (e.fromWalletId = :walletId or e.toWalletId = :walletId) " +
            "and e.occurredAt >= :start and e.occurredAt < :end and e.occurredAt < :cutoff")
    long countWalletEventsForPeriod(@Param("walletId") Long walletId,
                                    @Param("start") Instant start,
                                    @Param("end") Instant end,
                                    @Param("cutoff") Instant cutoff);
}
