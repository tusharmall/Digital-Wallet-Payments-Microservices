package com.example.wallet.service;

import com.example.wallet.entity.OutboxEvent;
import com.example.wallet.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final String deadLetterTopic;

    public OutboxPublisher(OutboxEventRepository repository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           @Value("${app.kafka.transactions-topic:transactions.events}") String topic,
                           @Value("${app.kafka.dead-letter-topic:transactions.events.DLT}") String deadLetterTopic) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.deadLetterTopic = deadLetterTopic;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = repository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            try {
                kafkaTemplate.send(topic, event.getId(), event.getPayload()).get(10, TimeUnit.SECONDS);
                event.setPublishedAt(Instant.now());
                event.setLastError(null);
            } catch (Exception firstFailure) {
                event.incrementAttempts();
                event.setLastError(truncate(firstFailure.getMessage()));
                if (event.getAttempts() >= 5) {
                    try {
                        kafkaTemplate.send(deadLetterTopic, event.getId(), event.getPayload()).get(10, TimeUnit.SECONDS);
                        event.setPublishedAt(Instant.now());
                        event.setLastError("Publishing failed repeatedly; moved to " + deadLetterTopic + ". " + truncate(firstFailure.getMessage()));
                        log.error("Outbox event {} sent to dead-letter topic after repeated failures", event.getId());
                    } catch (Exception dltFailure) {
                        event.setLastError(truncate("Primary publish failed: " + firstFailure.getMessage() + "; DLT publish failed: " + dltFailure.getMessage()));
                        log.error("Unable to publish outbox event {} or its dead-letter record", event.getId(), dltFailure);
                    }
                } else {
                    log.warn("Outbox event {} publish attempt {} failed", event.getId(), event.getAttempts(), firstFailure);
                }
            }
        }
    }

    private String truncate(String value) {
        if (value == null) return "Unknown publish error";
        return value.length() > 950 ? value.substring(0, 950) : value;
    }
}
