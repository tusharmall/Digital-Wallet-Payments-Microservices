package com.example.kyc.service;

import com.example.kyc.entity.StatementJob;
import com.example.kyc.entity.TransactionEventProjection;
import com.example.kyc.repository.TransactionEventProjectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class StatementWorker {
    private static final Logger log = LoggerFactory.getLogger(StatementWorker.class);
    private final StatementJobStateService stateService;
    private final TransactionEventProjectionRepository eventRepository;
    private final S3StorageService storageService;
    private final WalletOwnershipClient ownershipClient;
    private final long projectionWaitSeconds;
    private final long projectionPollMillis;

    public StatementWorker(StatementJobStateService stateService,
                           TransactionEventProjectionRepository eventRepository,
                           S3StorageService storageService,
                           WalletOwnershipClient ownershipClient,
                           @Value("${app.statements.projection-wait-seconds:60}") long projectionWaitSeconds,
                           @Value("${app.statements.projection-poll-millis:500}") long projectionPollMillis) {
        this.stateService = stateService;
        this.eventRepository = eventRepository;
        this.storageService = storageService;
        this.ownershipClient = ownershipClient;
        this.projectionWaitSeconds = projectionWaitSeconds;
        this.projectionPollMillis = projectionPollMillis;
    }

    @Async
    public void generateAsync(String jobId) {
        try {
            StatementJob job = stateService.markProcessing(jobId);
            if (job.getStatus().name().equals("COMPLETED")) return;
            YearMonth yearMonth = YearMonth.parse(job.getStatementMonth());
            Instant start = yearMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant end = yearMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            waitForProjection(job, start, end);
            List<TransactionEventProjection> events = eventRepository.findWalletEventsForPeriod(
                    job.getWalletId(), start, end, job.getCutoffAt());
            if (events.size() < job.getExpectedTransactionCount()) {
                throw new IllegalStateException("Transaction projection is behind the wallet service; refusing to publish a partial statement.");
            }
            String csv = createCsv(job.getWalletId(), events);
            String objectKey = "statements/" + job.getUserId() + "/" + job.getWalletId() + "/" + job.getStatementMonth() + "/" + job.getId() + ".csv";
            storageService.putCsv(objectKey, csv);
            stateService.markCompleted(jobId, objectKey);
        } catch (Exception ex) {
            log.error("Statement generation failed for job {}", jobId, ex);
            try { stateService.markFailed(jobId, ex.getMessage()); }
            catch (Exception markingFailure) { log.error("Unable to update failed statement job {}", jobId, markingFailure); }
        }
    }

    private void waitForProjection(StatementJob job, Instant start, Instant end) throws InterruptedException {
        long projected = eventRepository.countWalletEventsForPeriod(job.getWalletId(), start, end, job.getCutoffAt());
        if (projected >= job.getExpectedTransactionCount()) return;
        long deadline = System.nanoTime() + Duration.ofSeconds(projectionWaitSeconds).toNanos();
        while (projected < job.getExpectedTransactionCount() && System.nanoTime() < deadline) {
            Thread.sleep(projectionPollMillis);
            projected = eventRepository.countWalletEventsForPeriod(job.getWalletId(), start, end, job.getCutoffAt());
        }
        if (projected < job.getExpectedTransactionCount()) {
            throw new IllegalStateException("Kafka transaction projection did not catch up within " + projectionWaitSeconds + " seconds.");
        }
    }

    private String createCsv(Long walletId, List<TransactionEventProjection> events) {
        StringBuilder csv = new StringBuilder();
        csv.append("transaction_id,transaction_type,occurred_at,direction,from_wallet_id,to_wallet_id,amount,currency,status\r\n");
        for (TransactionEventProjection event : events) {
            String direction = walletId.equals(event.getToWalletId()) ? "CREDIT" : "DEBIT";
            csv.append(event.getTransactionId()).append(',')
                    .append(escape(event.getTransactionType())).append(',')
                    .append(escape(event.getOccurredAt().toString())).append(',')
                    .append(direction).append(',')
                    .append(value(event.getFromWalletId())).append(',')
                    .append(value(event.getToWalletId())).append(',')
                    .append(event.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString()).append(',')
                    .append(escape(event.getCurrency())).append(',')
                    .append(escape(event.getStatus())).append("\r\n");
        }
        return csv.toString();
    }

    private String value(Object value) { return value == null ? "" : value.toString(); }

    private String escape(String value) {
        if (value == null) return "";
        String safe = value.replace("\"", "\"\"");
        return safe.contains(",") || safe.contains("\n") || safe.contains("\r") || safe.contains("\"") ? "\"" + safe + "\"" : safe;
    }
}
