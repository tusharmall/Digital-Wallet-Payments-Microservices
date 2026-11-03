package com.example.kyc.controller;

import com.example.kyc.dto.KycDocumentResponse;
import com.example.kyc.dto.KycUploadRequest;
import com.example.kyc.dto.KycUploadResponse;
import com.example.kyc.dto.ReviewKycRequest;
import com.example.kyc.exception.BusinessException;
import com.example.kyc.service.KycDocumentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/kyc")
public class KycController {
    private final KycDocumentService service;
    public KycController(KycDocumentService service) { this.service = service; }

    @PostMapping("/upload-url")
    @ResponseStatus(HttpStatus.CREATED)
    public KycUploadResponse createUpload(@RequestHeader("X-User-Id") Long userId,
                                          @Valid @RequestBody KycUploadRequest request) {
        return service.createUpload(userId, request.fileName(), request.contentType());
    }

    @GetMapping("/{documentId}")
    public KycDocumentResponse get(@PathVariable String documentId,
                                   @RequestHeader("X-User-Id") Long userId) {
        return service.getDocument(documentId, userId);
    }

    @PatchMapping("/{documentId}/status")
    public KycDocumentResponse review(@PathVariable String documentId,
                                      @RequestHeader("X-User-Role") String roles,
                                      @RequestHeader("X-User-Id") String reviewerId,
                                      @Valid @RequestBody ReviewKycRequest request) {
        Set<String> roleSet = Arrays.stream(roles.toUpperCase(Locale.ROOT).split("[, ]+"))
                .collect(Collectors.toSet());
        if (!roleSet.contains("KYC_REVIEWER") && !roleSet.contains("ADMIN")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "KYC_REVIEW_FORBIDDEN", "A KYC_REVIEWER or ADMIN role is required.");
        }
        return service.review(documentId, request.status(), request.note(), reviewerId);
    }
}
