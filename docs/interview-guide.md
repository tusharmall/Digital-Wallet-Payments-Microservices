# Interview guide: Digital Wallet & Payments Service

## 60-second project explanation

“I built a two-service digital wallet using Java 17 and Spring Boot. The Wallet Service owns balances and a double-entry ledger. A transfer updates both wallet balances, writes debit and credit ledger entries, and inserts an outbox event in one MySQL transaction. I use fixed-order `SELECT FOR UPDATE` locks to prevent double-spending and an idempotency key backed by a database unique constraint so retries cannot move money twice. Redis provides a fast idempotency cache and rate limiting. An outbox publisher sends committed transaction events to Kafka. The KYC & Statement Service consumes them into a deduplicated projection, sends notifications, creates presigned S3 upload links for KYC documents and generates monthly CSV statements asynchronously. Testcontainers exercises MySQL, Kafka and Redis, including 100 concurrent transfers from one wallet.”

## Interview questions to practice

### Why use double-entry ledger rows if balances are already stored?
A balance is a fast current-state value; the ledger is the auditable record of movements. For a transfer of INR 50, the source gets a debit of 50 and the target gets a credit of 50. Both are written atomically. If balances or integrations need reconciliation, the ledger provides a traceable history.

### How do you prevent a negative balance?
Both source and destination wallet rows are locked using pessimistic locking in sorted wallet-ID order. After acquiring the locks, the service checks the source balance and updates both balances in the same transaction. Concurrent transfers from the same source serialize at the source row, so another request sees the committed updated balance.

### Why use a fixed lock order?
Without a fixed order, transfer A-to-B could lock A then wait for B, while transfer B-to-A locks B then waits for A. That creates a deadlock cycle. Sorting IDs ensures every request tries to lock the same pair in the same order.

### What does idempotency protect against?
Clients often retry after a timeout even when the first request succeeded. The `Idempotency-Key` identifies one business operation. The service stores a payload fingerprint and transaction response. A retry with the same key and same payload returns the original transaction; a different payload with that key returns HTTP 409. A database unique constraint is the final concurrent-request safeguard.

### Why not trust Redis for idempotency?
Redis may restart, evict data or lose a cache entry. It improves speed, but payment correctness belongs in durable relational storage. The unique constraint prevents a second transaction even if Redis has no entry.

### What is the outbox pattern?
The database transaction writes the wallet changes and the event payload to an outbox table together. A background publisher later sends those pending rows to Kafka. This avoids committing a wallet transfer without recording the event to publish. Delivery is at-least-once, so the consumer deduplicates events.

### Is it exactly-once processing?
No. The demo deliberately uses at-least-once delivery with idempotent consumption. A message can be sent and the publisher can crash before marking it published, causing a duplicate. The event ID primary key prevents duplicate projection rows. Email delivery can still be at-least-once, which is documented.

### Why Kafka for statements?
The KYC/Statement service gets a local read projection without querying the wallet database for each transaction. Event-driven projection reduces synchronous coupling and allows asynchronous notifications and statement exports. The service checks that its projection has caught up before completing a statement job.

### How are statements generated?
The API inserts a job and returns HTTP 202. An asynchronous worker changes it to PROCESSING, reads the transaction projection for the requested month, builds CSV, uploads it to S3, and marks the job COMPLETED. Polling the job returns a time-limited presigned download URL.

### Why presigned URLs instead of proxying uploads?
The client sends file bytes directly to S3/MinIO. The service handles authorization, metadata and URL expiry, not the large file stream. This reduces application bandwidth and makes object-storage policies easier to apply.

### What would you harden before production?
Integrate a proper identity provider and authorization at the gateway, enforce ownership using trusted claims, disable simulated funding, use IAM roles and private networking, add reconciliation/ledger-balance controls, handle KYC/AML compliance, add malware scanning and object-retention policies, monitor consumer lag/DLTs, and test backup, failover and recovery.

## Important honesty point

Do not claim a measured TPS or p95 until you run the included k6 test on a known machine/environment and save the output. Do not describe simulated `add-money` as a real payment-gateway integration.
