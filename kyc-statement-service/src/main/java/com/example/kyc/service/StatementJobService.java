package com.example.kyc.service;

import com.example.kyc.dto.CreateStatementRequest;
import com.example.kyc.dto.StatementJobResponse;
import com.example.kyc.entity.StatementJob;
import com.example.kyc.entity.StatementStatus;
import com.example.kyc.exception.BusinessException;
import com.example.kyc.repository.StatementJobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.UUID;

@Service
public class StatementJobService {
    private final StatementJobRepository repository;
    private final WalletOwnershipClient ownershipClient;
    private final StatementWorker worker;
    private final S3StorageService storageService;
    private final TransactionTemplate transactionTemplate;

    public StatementJobService(StatementJobRepository repository,
                               WalletOwnershipClient ownershipClient,
                               StatementWorker worker,
                               S3StorageService storageService,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.ownershipClient = ownershipClient;
        this.worker = worker;
        this.storageService = storageService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public StatementJobResponse request(Long userId, CreateStatementRequest request) {
        ownershipClient.assertOwner(request.walletId(), userId);
        YearMonth yearMonth = YearMonth.parse(request.month());
        if (yearMonth.isAfter(YearMonth.now(ZoneOffset.UTC))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "FUTURE_STATEMENT_MONTH", "A statement cannot be requested for a future month.");
        }
        Instant cutoff = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant start = yearMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = yearMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        long expectedCount = ownershipClient.countTransactions(request.walletId(), start, end, cutoff);
        StatementJob job = transactionTemplate.execute(status -> {
            StatementJob newJob = new StatementJob();
            newJob.setId(UUID.randomUUID().toString());
            newJob.setUserId(userId);
            newJob.setWalletId(request.walletId());
            newJob.setStatementMonth(request.month());
            newJob.setCutoffAt(cutoff);
            newJob.setExpectedTransactionCount(expectedCount);
            newJob.setStatus(StatementStatus.PENDING);
            return repository.saveAndFlush(newJob);
        });
        if (job == null) throw new IllegalStateException("Unable to create statement job.");
        // TransactionTemplate commits before the asynchronous worker is submitted.
        worker.generateAsync(job.getId());
        return toResponse(job, null, null);
    }

    public StatementJobResponse get(String jobId, Long userId) {
        StatementJob job = repository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "STATEMENT_JOB_NOT_FOUND", "Statement job not found."));
        String downloadUrl = null;
        Instant expiry = null;
        if (job.getStatus() == StatementStatus.COMPLETED && job.getObjectKey() != null) {
            S3StorageService.PresignedDownload presigned = storageService.presignDownload(job.getObjectKey());
            downloadUrl = presigned.url().toString();
            expiry = presigned.expiresAt();
        }
        return toResponse(job, downloadUrl, expiry);
    }

    private StatementJobResponse toResponse(StatementJob job, String downloadUrl, Instant expiry) {
        return new StatementJobResponse(job.getId(), job.getWalletId(), job.getStatementMonth(), job.getStatus(),
                job.getCreatedAt(), job.getCompletedAt(), downloadUrl, expiry, job.getErrorMessage());
    }
}
