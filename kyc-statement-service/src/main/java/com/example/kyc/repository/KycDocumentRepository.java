package com.example.kyc.repository;

import com.example.kyc.entity.KycDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KycDocumentRepository extends JpaRepository<KycDocument, String> {
    Optional<KycDocument> findByIdAndUserId(String id, Long userId);
}
