package com.example.kyc.service;

import com.example.kyc.dto.KycDocumentResponse;
import com.example.kyc.dto.KycUploadResponse;
import com.example.kyc.entity.KycDocument;
import com.example.kyc.entity.KycStatus;
import com.example.kyc.exception.BusinessException;
import com.example.kyc.repository.KycDocumentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class KycDocumentService {
    private final KycDocumentRepository repository;
    private final S3StorageService storageService;

    public KycDocumentService(KycDocumentRepository repository, S3StorageService storageService) {
        this.repository = repository;
        this.storageService = storageService;
    }

    @Transactional
    public KycUploadResponse createUpload(Long userId, String fileName, String rawContentType) {
        String contentType = rawContentType.trim().toLowerCase(Locale.ROOT);
        if (!Map.of("application/pdf", true, "image/jpeg", true, "image/png", true).containsKey(contentType)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE",
                    "Only PDF, JPEG and PNG KYC files are accepted.");
        }
        String cleanFileName = cleanFileName(fileName);
        String documentId = UUID.randomUUID().toString();
        String objectKey = "kyc/" + userId + "/" + documentId + "/" + cleanFileName;

        KycDocument document = new KycDocument();
        document.setId(documentId);
        document.setUserId(userId);
        document.setOriginalFileName(cleanFileName);
        document.setContentType(contentType);
        document.setObjectKey(objectKey);
        document.setStatus(KycStatus.PENDING);
        repository.saveAndFlush(document);

        S3StorageService.PresignedUpload presigned = storageService.presignUpload(objectKey, contentType);
        return new KycUploadResponse(documentId, presigned.url().toString(), objectKey,
                document.getStatus(), presigned.expiresAt(), Map.of("Content-Type", contentType));
    }

    @Transactional(readOnly = true)
    public KycDocumentResponse getDocument(String documentId, Long userId) {
        KycDocument document = repository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "KYC_DOCUMENT_NOT_FOUND", "KYC document not found."));
        return toResponse(document);
    }

    @Transactional
    public KycDocumentResponse review(String documentId, KycStatus requestedStatus, String note, String reviewer) {
        if (requestedStatus == null || requestedStatus == KycStatus.PENDING) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_KYC_STATUS", "Review status must be VERIFIED or REJECTED.");
        }
        KycDocument document = repository.findById(documentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "KYC_DOCUMENT_NOT_FOUND", "KYC document not found."));
        if (document.getStatus() != KycStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT, "KYC_ALREADY_REVIEWED", "Only pending KYC documents can be reviewed.");
        }
        if (requestedStatus == KycStatus.VERIFIED && !storageService.objectExists(document.getObjectKey())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "KYC_FILE_NOT_UPLOADED", "The document object is not present in storage yet.");
        }
        document.setStatus(requestedStatus);
        document.setReviewedBy(reviewer);
        document.setReviewNote(note == null ? null : note.trim());
        return toResponse(repository.save(document));
    }

    private String cleanFileName(String fileName) {
        String normalized = fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        normalized = normalized.replaceAll("[^A-Za-z0-9._-]", "_");
        if (normalized.isBlank() || normalized.equals(".") || normalized.equals("..")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FILE_NAME", "The supplied file name is invalid.");
        }
        return normalized.length() > 200 ? normalized.substring(normalized.length() - 200) : normalized;
    }

    private KycDocumentResponse toResponse(KycDocument document) {
        return new KycDocumentResponse(document.getId(), document.getUserId(), document.getOriginalFileName(),
                document.getContentType(), document.getStatus(), document.getCreatedAt(), document.getUpdatedAt(), document.getReviewNote());
    }
}
