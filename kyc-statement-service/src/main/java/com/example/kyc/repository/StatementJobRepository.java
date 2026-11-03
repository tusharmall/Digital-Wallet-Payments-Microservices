package com.example.kyc.repository;

import com.example.kyc.entity.StatementJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StatementJobRepository extends JpaRepository<StatementJob, String> {
    Optional<StatementJob> findByIdAndUserId(String id, Long userId);
}
