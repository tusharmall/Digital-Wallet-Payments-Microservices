package com.example.kyc.service;

import com.example.kyc.dto.WalletTransactionEvent;
import com.example.kyc.entity.TransactionEventProjection;
import com.example.kyc.exception.BusinessException;
import com.example.kyc.repository.TransactionEventProjectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final TransactionEventProjectionRepository repository;
    private final JavaMailSender mailSender;
    private final String fromAddress;

    public TransactionEventConsumer(ObjectMapper objectMapper,
                                    TransactionEventProjectionRepository repository,
                                    JavaMailSender mailSender,
                                    @Value("${app.notifications.from-address:no-reply@wallet.local}") String fromAddress) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            dltTopicSuffix = ".DLT",
            autoCreateTopics = "true"
    )
    @KafkaListener(topics = "${app.kafka.transactions-topic:transactions.events}",
            groupId = "${spring.kafka.consumer.group-id:kyc-statement-service}")
    @Transactional
    public void consume(String payload) throws Exception {
        WalletTransactionEvent event = objectMapper.readValue(payload, WalletTransactionEvent.class);
        if (event.eventId() == null || event.transactionId() == null || event.amount() == null || event.occurredAt() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION_EVENT", "Required transaction event fields are missing.");
        }

        TransactionEventProjection projection = repository.findById(event.eventId()).orElseGet(() -> {
            TransactionEventProjection newProjection = new TransactionEventProjection();
            newProjection.setEventId(event.eventId());
            newProjection.setTransactionId(event.transactionId());
            newProjection.setTransactionType(event.transactionType());
            newProjection.setFromWalletId(event.fromWalletId());
            newProjection.setToWalletId(event.toWalletId());
            newProjection.setFromUserId(event.fromUserId());
            newProjection.setToUserId(event.toUserId());
            newProjection.setFromEmail(event.fromEmail());
            newProjection.setToEmail(event.toEmail());
            newProjection.setAmount(event.amount());
            newProjection.setCurrency(event.currency());
            newProjection.setStatus(event.status());
            newProjection.setOccurredAt(event.occurredAt());
            newProjection.setNotificationSent(false);
            return repository.saveAndFlush(newProjection);
        });

        // The projection's primary key makes event consumption idempotent. Notification retries may be
        // at-least-once if a process crashes after SMTP accepts mail but before this DB transaction commits.
        if (!projection.isNotificationSent()) {
            notifyRecipient(event.fromEmail(), "Wallet transaction " + event.transactionId(), notificationBody(event, "debit"));
            if (event.toEmail() != null && !event.toEmail().isBlank() && !event.toEmail().equalsIgnoreCase(event.fromEmail())) {
                notifyRecipient(event.toEmail(), "Wallet transaction " + event.transactionId(), notificationBody(event, "credit"));
            }
            projection.setNotificationSent(true);
            repository.save(projection);
        }
    }

    @DltHandler
    public void handleDeadLetter(String payload) {
        // DLT records are deliberately retained by Kafka for investigation/replay. Alerting can be added here.
        log.error("Transaction event reached dead-letter topic: {}", payload);
    }

    private void notifyRecipient(String recipient, String subject, String body) {
        if (recipient == null || recipient.isBlank()) return;
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
        } catch (MailException ex) {
            throw ex;
        }
    }

    private String notificationBody(WalletTransactionEvent event, String side) {
        return "A wallet transaction was completed.\n\n" +
                "Transaction ID: " + event.transactionId() + "\n" +
                "Type: " + event.transactionType() + "\n" +
                "Amount: " + event.currency() + " " + event.amount().toPlainString() + "\n" +
                "Entry: " + side.toUpperCase() + "\n" +
                "Status: " + event.status() + "\n" +
                "Time: " + event.occurredAt() + "\n\n" +
                "This is a demo notification. Contact support if you do not recognize this activity.";
    }
}
