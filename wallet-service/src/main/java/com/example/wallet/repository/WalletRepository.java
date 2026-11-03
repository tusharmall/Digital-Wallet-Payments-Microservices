package com.example.wallet.repository;

import com.example.wallet.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id = :walletId")
    Optional<Wallet> findByIdForUpdate(@Param("walletId") Long walletId);

    @Query("select w.user.id from Wallet w where w.id = :walletId")
    Optional<Long> findOwnerIdByWalletId(@Param("walletId") Long walletId);

    @Query("select w.balance from Wallet w where w.id = :walletId")
    Optional<java.math.BigDecimal> findBalanceByWalletId(@Param("walletId") Long walletId);
}
