# Architecture and design decisions

## Service boundaries

### Wallet Service
Owns users, wallet balances, wallet transactions, ledger entries and the outbox. It is the only writer for money state. No other service should update wallet balances directly.

### KYC & Statement Service
Owns KYC document metadata, statement jobs and a read projection of wallet transaction events. It can create presigned URLs and export statements, but it never writes the wallet ledger.

## Core invariants

1. Every successful movement has exactly two ledger entries of equal amount: one debit and one credit.
2. The transaction row, balance updates, ledger entries and outbox event commit atomically in one MySQL transaction.
3. Source and destination wallet rows are locked in sorted wallet-ID order before the balance check and write.
4. A transaction is only replayed for the same authenticated requester, idempotency key and request fingerprint.
5. Ledger/transaction rows are immutable in the normal request path. Corrections should be compensating entries, not deletion or overwriting.
6. KYC/statement service consumes events idempotently using the event ID as a primary key.
7. Statements are produced only after the local event projection has caught up with the wallet service's transaction count for the requested period. If it times out, the job fails rather than claiming it is complete.

## Transfer sequence

```text
Client -> API Gateway -> Wallet Service
                            |
                            | 1. Validate gateway identity, source ownership,
                            |    payload, Idempotency-Key and fraud/rate rules
                            v
                      MySQL transaction
                        - lock lower wallet ID
                        - lock higher wallet ID
                        - check funds/daily limit/velocity
                        - update both balances
                        - insert transaction row
                        - insert debit + credit ledger rows
                        - insert outbox event
                            |
                            | COMMIT
                            v
                    Outbox publisher -> Kafka
                                          |
                                          v
                             KYC & Statement Service
                               - deduplicate event
                               - persist projection
                               - send notification
                               - use projection for statements
```

## Why the outbox pattern

Publishing to Kafka before committing the MySQL transaction can create an event for a transfer that rolled back. Publishing after commit without an outbox can lose an event if the process crashes between commit and publish. Writing an outbox row in the same database transaction avoids this divergence. The publisher can send duplicates after a crash, so the consumer deduplicates by `eventId`.

## Concurrency and idempotency

The code uses pessimistic row locks because transfer correctness must be explicit and a fixed lock order avoids A-to-B / B-to-A lock inversion. The `@Version` field is an additional optimistic-lock guard if a future code path updates a wallet without a row lock.

Redis provides a fast response cache for idempotency and a fixed-window rate limit. It is not the final duplicate-payment safeguard. MySQL's unique constraint is the authoritative guard if identical requests arrive simultaneously or Redis data expires.

## Statement projection consistency

The statement worker asks the wallet service for the count of transaction rows touching the requested wallet in the requested month. It compares that count against the count in the KYC service's Kafka projection and waits briefly for consumer lag to clear. If the count does not converge, the job fails. This is intentionally safer than silently exporting a partial statement. A production platform should use an explicit projection watermark, Kafka offset tracking, or a versioned statement snapshot rather than relying only on count convergence.

## Known demo simplifications

- New users and their wallets are created together; there is no full user identity or KYC onboarding engine.
- `add-money` simulates a funding event, not a payment gateway.
- Email is best-effort through SMTP. If the SMTP server accepts the message and the process crashes before the database transaction commits, the message can be sent again on retry.
- The local compose file includes MinIO and Mailpit for convenience. AWS deployments should use IAM roles and pre-created buckets.
