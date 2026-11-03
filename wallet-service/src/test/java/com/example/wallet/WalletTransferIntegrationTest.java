package com.example.wallet;

import com.example.wallet.dto.CreateUserRequest;
import com.example.wallet.dto.TransactionResponse;
import com.example.wallet.dto.TransferRequest;
import com.example.wallet.dto.UserWalletResponse;
import com.example.wallet.exception.BusinessException;
import com.example.wallet.repository.LedgerEntryRepository;
import com.example.wallet.repository.OutboxEventRepository;
import com.example.wallet.service.OutboxPublisher;
import com.example.wallet.service.WalletApplicationService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WalletTransferIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("wallet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.outbox.poll-interval-ms", () -> 60000);
        registry.add("wallet.transfer.daily-limit", () -> "1000000.00");
        registry.add("wallet.transfer.max-single-amount", () -> "50000.00");
        registry.add("wallet.fraud.max-transfers-per-minute", () -> 1000);
        registry.add("wallet.rate-limit.transfers-per-minute", () -> 1000);
    }

    @Autowired WalletApplicationService walletService;
    @Autowired LedgerEntryRepository ledgerRepository;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired OutboxPublisher outboxPublisher;

    @Test
    void sameIdempotencyKeyMovesMoneyOnlyOnceAndWritesBalancedLedger() {
        UserWalletResponse sender = createUser("sender-idem");
        UserWalletResponse recipient = createUser("recipient-idem");
        walletService.addMoney(sender.walletId(), sender.userId(), new BigDecimal("100.00"), "fund-" + UUID.randomUUID());

        String key = "transfer-" + UUID.randomUUID();
        TransferRequest request = new TransferRequest(sender.walletId(), recipient.walletId(), new BigDecimal("15.00"));
        TransactionResponse first = walletService.transfer(request, sender.userId(), key);
        TransactionResponse retry = walletService.transfer(request, sender.userId(), key);

        assertEquals(first.transactionId(), retry.transactionId());
        assertEquals(new BigDecimal("85.00"), walletService.getBalance(sender.walletId(), sender.userId()).balance());
        assertEquals(new BigDecimal("15.00"), walletService.getBalance(recipient.walletId(), recipient.userId()).balance());
        assertEquals(2, ledgerRepository.countByTransactionId(first.transactionId()));
        assertTrue(outboxRepository.findAll().stream().anyMatch(event -> event.getAggregateId().equals(first.transactionId().toString())));

        TransferRequest differentPayload = new TransferRequest(sender.walletId(), recipient.walletId(), new BigDecimal("16.00"));
        BusinessException conflict = assertThrows(BusinessException.class,
                () -> walletService.transfer(differentPayload, sender.userId(), key));
        assertEquals("IDEMPOTENCY_KEY_REUSED", conflict.getCode());
    }

    @Test
    void outboxPublisherDeliversTransactionEventToKafka() {
        UserWalletResponse sender = createUser("sender-kafka");
        TransactionResponse funding = walletService.addMoney(sender.walletId(), sender.userId(),
                new BigDecimal("25.00"), "fund-kafka-" + UUID.randomUUID());

        outboxPublisher.publishPendingEvents();

        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "wallet-outbox-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        boolean eventFound = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of("transactions.events"));
            while (!eventFound && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.value().contains("\"transactionId\":" + funding.transactionId() + ",")) {
                        eventFound = true;
                        break;
                    }
                }
            }
        }
        assertTrue(eventFound, "The committed outbox event should be published to Kafka.");
    }

    @Test
    void oneHundredConcurrentTransfersCannotOverdrawSourceWallet() throws Exception {
        UserWalletResponse sender = createUser("sender-concurrency");
        UserWalletResponse recipient = createUser("recipient-concurrency");
        walletService.addMoney(sender.walletId(), sender.userId(), new BigDecimal("1000.00"), "fund-" + UUID.randomUUID());

        ExecutorService executor = Executors.newFixedThreadPool(20);
        AtomicInteger successes = new AtomicInteger();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int index = i;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    walletService.transfer(new TransferRequest(sender.walletId(), recipient.walletId(), new BigDecimal("20.00")),
                            sender.userId(), "parallel-" + UUID.randomUUID() + "-" + index);
                    successes.incrementAndGet();
                } catch (BusinessException ex) {
                    if (!"INSUFFICIENT_FUNDS".equals(ex.getCode())) throw ex;
                }
            }, executor));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(90, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(50, successes.get(), "Only fifty INR 20 transfers should fit in an INR 1,000 balance.");
        assertEquals(new BigDecimal("0.00"), walletService.getBalance(sender.walletId(), sender.userId()).balance());
        assertEquals(new BigDecimal("1000.00"), walletService.getBalance(recipient.walletId(), recipient.userId()).balance());
    }

    private UserWalletResponse createUser(String prefix) {
        return walletService.createUser(new CreateUserRequest(prefix + " User", prefix + "." + UUID.randomUUID() + "@example.test"));
    }
}
