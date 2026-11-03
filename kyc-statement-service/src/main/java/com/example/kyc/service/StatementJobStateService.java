package com.example.kyc.service;

import com.example.kyc.entity.StatementJob;
import com.example.kyc.entity.StatementStatus;
import com.example.kyc.exception.BusinessException;
import com.example.kyc.repository.StatementJobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class StatementJobStateService {
    private final StatementJobRepository repository;

    public StatementJobStateService(StatementJobRepository repository) { this.repository = repository; }

    @Transactional
    public StatementJob markProcessing(String jobId) {
        StatementJob job = requireJob(jobId);
        if (job.getStatus() == StatementStatus.COMPLETED) return job;
        job.setStatus(StatementStatus.PROCESSING);
        job.setErrorMessage(null);
        return repository.save(job);
    }

    @Transactional
    public void markCompleted(String jobId, String objectKey) {
        StatementJob job = requireJob(jobId);
        job.setStatus(StatementStatus.COMPLETED);
        job.setObjectKey(objectKey);
        job.setErrorMessage(null);
        job.setCompletedAt(Instant.now());
        repository.save(job);
    }

    @Transactional
    public void markFailed(String jobId, String error) {
        StatementJob job = requireJob(jobId);
        job.setStatus(StatementStatus.FAILED);
        job.setErrorMessage(error == null ? "Statement generation failed." : error.substring(0, Math.min(error.length(), 950)));
        repository.save(job);
    }

    @Transactional(readOnly = true)
    public StatementJob get(String jobId) { return requireJob(jobId); }

    private StatementJob requireJob(String jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "STATEMENT_JOB_NOT_FOUND", "Statement job not found."));
    }
}
