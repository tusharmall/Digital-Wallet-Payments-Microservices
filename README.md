# Digital Wallet & Payments Service

A portfolio-quality Spring Boot microservices project for demonstrating money movement, ledger consistency, idempotency, concurrency control, event-driven processing, S3 uploads and asynchronous statements.

<img width="1916" height="1008" alt="Screenshot 2026-11-21 202209" src="https://github.com/user-attachments/assets/310ad1dc-e64d-47af-b103-2df391de2c6d" />
<img width="1887" height="1010" alt="Screenshot 2026-11-21 202324" src="https://github.com/user-attachments/assets/0dee750f-e67c-496c-9bb7-6d13816fe9fd" />
# KYC
<img width="1886" height="977" alt="image" src="https://github.com/user-attachments/assets/9531f0b7-560e-4164-b62d-fef6bf55dc9f" />





> **Important:** this is a learning/demo system, not a real financial product. Add identity-provider integration, compliance controls, penetration testing, key management, operational monitoring, and a regulated payment/funding integration before any production use. The `add-money` endpoint simulates funding; it does not charge a bank or card.

## Architecture

```text
                 Your EXISTING API Gateway
                 /                       \
                v                         v
        Wallet Service             KYC & Statement Service
        Spring Boot :8081          Spring Boot :8082
        - users / wallets          - KYC status tracking
        - transfer + top-up        - presigned S3 upload URLs
        - double-entry ledger      - async CSV statement jobs
        - idempotency              - notifications via SMTP
        - daily/velocity rules     - Kafka event projection
                |                         ^
                |                         |
        MySQL wallet_db                 Kafka
                |                 transactions.events
                |                         ^
           Outbox table ------------------+
                |
        Redis: key cache + rate limiting

  KYC Service additionally uses MySQL kyc_db, S3/MinIO and Mailpit.
```

There are exactly two application services. The gateway is not included because this project is designed to sit behind your existing gateway. `infra/gateway-routes.yml` is an example route fragment, not a third gateway service.

## What is implemented

### Wallet Service
- Create a user and one INR wallet.
- Simulated add-money and wallet-to-wallet transfers.
- Two ledger rows per successful movement, created in the same MySQL transaction as the wallet balance changes.
- Persistent idempotency using a unique `(requested_by_user_id, idempotency_key)` constraint. Redis caches a completed response for 24 hours, but the database remains the correctness authority.
- Transfers lock both wallet rows with `SELECT ... FOR UPDATE`, always in ascending wallet-ID order. The lock order prevents the classic A-to-B / B-to-A deadlock pattern; the locked balance check prevents overdraws.
- Optimistic `@Version` column is retained as a second guard against unexpected non-locking updates.
- Daily outgoing-transfer cap, single-transfer cap, a successful-transfer velocity rule and Redis fixed-window request limiting.
- Transaction history uses pageable database queries.
- Transactional outbox: the ledger/transaction and event record commit together. A scheduled publisher sends pending outbox events to Kafka; repeated send failures go to a dead-letter topic. A crash after Kafka accepts a message but before the database update can produce duplicates, so consumers are idempotent.

### KYC & Statement Service
- Generates presigned PUT URLs for PDF/JPEG/PNG KYC documents. Files upload directly to S3-compatible storage; the app does not proxy file bytes.
- Tracks KYC states `PENDING`, `VERIFIED`, and `REJECTED`. A reviewer can only transition a pending document to a final state; verification checks that the object exists.
- Consumes transaction events into a local projection, deduplicated by `eventId`, and sends basic transaction notifications through SMTP.
- Retries transient consumer failures and routes exhausted messages to a Kafka `.DLT` topic.
- Generates monthly CSV statements asynchronously, stores them in S3, and returns a presigned GET URL valid for 10 minutes.
- Uses an internal wallet-service endpoint to validate the wallet owner and confirm the transaction count before producing a statement. If the projection does not catch up before the configured wait expires, the job fails instead of silently exporting a partial statement.

## Technology stack

- Java 17, Spring Boot 3.4.x, Spring MVC, Spring Data JPA, Bean Validation
- MySQL 8.4 and Flyway migrations
- Redis for the idempotency response cache and rate limits
- Kafka with a transactional outbox publisher, idempotent projection consumer, retries and DLT
- AWS SDK v2 S3 client and presigner; MinIO is used for local development
- Mailpit for local email inspection
- Maven, Docker Compose, GitHub Actions, Testcontainers and k6

## Requirements

For the easiest local run, install Docker Desktop and Docker Compose. For building without Docker, install JDK 17 and Maven 3.9+.

## Start all local dependencies and services

From this repository's root directory:

```bash
cp .env.example .env
# Review .env and keep the values local-only.
docker compose up --build -d
```

On Windows PowerShell, `Copy-Item .env.example .env` is the equivalent of `cp`.

Wait for migrations and startup:

```bash
docker compose ps
docker compose logs -f wallet-service kyc-statement-service
```

Health endpoints:

- Wallet Service: `http://localhost:8081/actuator/health`
- KYC & Statement Service: `http://localhost:8082/actuator/health`
- Mailpit inbox: `http://localhost:8025`
- MinIO console: `http://localhost:9001` (local credentials are in `.env`)

The Docker Compose file exposes service ports for local development. In a deployment, keep both app services private and route public traffic through your existing API Gateway.

### Build/test with Maven

```bash
mvn -B clean verify
```

The wallet integration tests use Testcontainers to start real MySQL, Kafka and Redis containers. Docker must be available to run them. The test suite checks idempotent retries, balanced ledger writes, actual outbox-to-Kafka delivery, and 100 concurrent transfers on one source wallet.

### Stop local infrastructure

```bash
docker compose down
```

To also delete local database, Redis and MinIO data (destructive):

```bash
docker compose down -v
```

## API walkthrough

All wallet-owned endpoints expect `X-User-Id`; KYC review expects `X-User-Role` as well. These are **trusted gateway-to-service headers**. Your API Gateway must validate the user's JWT/session, strip any inbound client-supplied copies of these headers, and inject the verified identity and roles. Do not expose or trust these headers directly from untrusted clients in a real deployment.

### 1. Create two demo users

```bash
curl -s -X POST http://localhost:8081/api/users \
  -H 'Content-Type: application/json' \
  -d '{"fullName":"Asha Sender","email":"asha.sender@example.test"}'

curl -s -X POST http://localhost:8081/api/users \
  -H 'Content-Type: application/json' \
  -d '{"fullName":"Ravi Receiver","email":"ravi.receiver@example.test"}'
```

Save each response's `userId` and `walletId`. Example response shape:

```json
{
  "userId": 1,
  "fullName": "Asha Sender",
  "email": "asha.sender@example.test",
  "walletId": 1,
  "currency": "INR",
  "balance": 0.00
}
```

### 2. Simulate funding the sender wallet

Replace `SENDER_USER_ID` and `SENDER_WALLET_ID` with the response values. Use a unique idempotency key for every new business operation.

```bash
curl -s -X POST http://localhost:8081/api/wallets/SENDER_WALLET_ID/add-money \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: SENDER_USER_ID' \
  -H 'Idempotency-Key: fund-demo-001' \
  -d '{"amount":1000.00}'
```

Repeated calls with the same key and same request return the original transaction. Reusing that key with a different payload returns HTTP 409.

### 3. Transfer money

```bash
curl -s -X POST http://localhost:8081/api/transfers \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: SENDER_USER_ID' \
  -H 'Idempotency-Key: transfer-demo-001' \
  -d '{"fromWalletId":SENDER_WALLET_ID,"toWalletId":RECIPIENT_WALLET_ID,"amount":125.50}'
```

### 4. Check balance and transaction history

```bash
curl -s http://localhost:8081/api/wallets/SENDER_WALLET_ID/balance \
  -H 'X-User-Id: SENDER_USER_ID'

curl -s 'http://localhost:8081/api/wallets/SENDER_WALLET_ID/transactions?page=0&size=20' \
  -H 'X-User-Id: SENDER_USER_ID'
```

### 5. Upload a KYC document using a presigned URL

Create an upload URL (the sample assumes you have a local `identity.pdf`):

```bash
curl -s -X POST http://localhost:8082/api/kyc/upload-url \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: SENDER_USER_ID' \
  -d '{"fileName":"identity.pdf","contentType":"application/pdf"}'
```

Take `uploadUrl` from the response and upload the file directly to MinIO/S3 with the exact `Content-Type` returned in `requiredHeaders`:

```bash
curl -X PUT 'UPLOAD_URL_FROM_RESPONSE' \
  -H 'Content-Type: application/pdf' \
  --data-binary '@identity.pdf'
```

The response contains a document ID. Review a successfully uploaded file using a trusted reviewer identity:

```bash
curl -s -X PATCH http://localhost:8082/api/kyc/DOCUMENT_ID/status \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 9001' \
  -H 'X-User-Role: KYC_REVIEWER' \
  -d '{"status":"VERIFIED","note":"Document checked"}'
```

For this local demo, role headers are manually supplied. In a real system, the gateway must derive them from verified authorization claims and must not accept client-provided roles.

### 6. Generate a monthly statement

```bash
curl -s -X POST http://localhost:8082/api/statements \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: SENDER_USER_ID' \
  -d '{"walletId":SENDER_WALLET_ID,"month":"2026-10"}'
```

The API returns HTTP 202 with a `jobId`. Poll the job:

```bash
curl -s http://localhost:8082/api/statements/JOB_ID \
  -H 'X-User-Id: SENDER_USER_ID'
```

When `status` is `COMPLETED`, `downloadUrl` is a presigned URL and `downloadUrlExpiresAt` tells you when to request a fresh URL. The job can be `FAILED` if the consumed Kafka projection does not catch up or storage is unavailable; inspect `errorMessage` and service logs.

## Kafka and outbox behavior

1. A wallet request starts a MySQL transaction.
2. The wallet rows are locked and checked; the balances, two ledger entries, transaction row and outbox row are written in one database transaction.
3. A scheduled publisher sends the stored outbox payload to `transactions.events`.
4. The KYC service saves the event using `eventId` as its primary key and attempts email notification through Mailpit/SMTP.
5. Consumer failures are retried and eventually sent to retry/DLT topics. The outbox publisher also routes events to `transactions.events.DLT` after repeated publication failure.

The guarantee is **at-least-once delivery with idempotent consumption**, not end-to-end exactly-once delivery. Outbox events are not deleted automatically; configure retention/archival in production.

## Load testing with k6

`load-test/k6-transfers.js` assumes pre-created sender/recipient wallets and a funded sender. Run:

```bash
k6 run -e BASE_URL=http://localhost:8081 \
  -e USER_ID=SENDER_USER_ID \
  -e FROM_WALLET_ID=SENDER_WALLET_ID \
  -e TO_WALLET_ID=RECIPIENT_WALLET_ID \
  load-test/k6-transfers.js
```

This script emits HTTP latency metrics and checks. **Do not quote throughput or p95 latency until you run it on a recorded environment**; performance depends on machine, database, Docker resources, and test parameters.

## Key configuration

See `.env.example` and the two `application.yml` files. Important settings include:

- `WALLET_DAILY_TRANSFER_LIMIT`, `WALLET_MAX_SINGLE_TRANSFER`
- `WALLET_FRAUD_MAX_PER_MINUTE`, `WALLET_RATE_LIMIT_PER_MINUTE`
- `OUTBOX_POLL_INTERVAL_MS`
- `TRANSACTIONS_TOPIC`, `TRANSACTIONS_DLT_TOPIC`
- `S3_BUCKET`, `APP_S3_ENDPOINT`, `APP_S3_PRESIGN_ENDPOINT`, `S3_UPLOAD_URL_MINUTES`, `S3_DOWNLOAD_URL_MINUTES`
- `INTERNAL_SERVICE_TOKEN`, `WALLET_SERVICE_BASE_URL`

For AWS, omit the S3 endpoint overrides, use an IAM role rather than static credentials, provision the bucket and policies with infrastructure-as-code, and set `S3_AUTO_CREATE_BUCKET=false`.

## Deploy to your existing Kubernetes infrastructure

The repository also includes a Helm chart at `deploy/helm/digital-wallet`. It deploys only the two application services; it deliberately does not create new MySQL, Kafka, Redis, Vault, or API Gateway infrastructure. Configure those to use your existing staging/prod endpoints.

Before installing the chart:

1. Configure GitHub Actions repository secrets `JFROG_REGISTRY`, `JFROG_USERNAME`, and `JFROG_PASSWORD`. The `publish-images.yml` workflow runs tests and publishes both images to `docker-local/digital-wallet/` in JFrog.
2. Update `deploy/helm/digital-wallet/values.yaml` with the correct JFrog repositories, Kafka bootstrap endpoint, Redis host, SMTP relay, S3 bucket/region, replica counts and resource limits.
3. Use your existing Vault integration to provide Kubernetes Secrets named `wallet-service-secrets` and `kyc-statement-service-secrets` (or change the values). The Wallet secret must provide `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, and `INTERNAL_SERVICE_TOKEN`. The KYC secret must provide its matching datasource values and the **same** internal service token. If your SMTP relay requires authentication, also provide `SPRING_MAIL_USERNAME` and `SPRING_MAIL_PASSWORD`, then enable `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH`. For S3, prefer the cluster's IAM/workload identity; only use static access keys if your platform requires them.
4. Ensure the database users have access to their respective databases and Flyway can apply migrations. Keep `/internal/**` off the public gateway and set trusted user/role headers only after authentication.

Install/upgrade after CI has pushed an image tag (replace `IMAGE_TAG` and `NAMESPACE`):

```bash
helm upgrade --install wallet-app deploy/helm/digital-wallet \
  --namespace NAMESPACE --create-namespace \
  --set walletService.image.tag=IMAGE_TAG \
  --set kycStatementService.image.tag=IMAGE_TAG
```

The service names are generated from the Helm release name and chart name. Use the resulting `*-wallet` and `*-kyc-statement` ClusterIP service names in your existing API Gateway routes. `infra/gateway-routes.yml` illustrates the route paths; it is not a replacement for your deployed gateway or its authentication filters.

## Production hardening checklist

- Integrate verified identity, authorization and account ownership with your actual gateway/JWT provider.
- Replace the simulated add-money flow with an audited funding workflow; never accept arbitrary top-ups from a public endpoint.
- Use managed MySQL/Redis/Kafka, TLS, secret management and restricted network policies.
- Add database migration review, reconciliation jobs, ledger balancing checks and operational repair procedures.
- Consider an explicit transfer state machine, durable payment-provider references and compensating flows for real external payments.
- Add KYC policy enforcement before transfer, AML/sanctions screening and regulator-required controls.
- Enforce S3 object size limits, malware scanning, encryption, bucket policy, CORS and lifecycle retention.
- Add notification outbox/delivery tracking if notifications are business-critical.
- Add consumer lag monitoring, DLT alerts/replay tooling, tracing, structured audit logs and dashboards.
- Review idempotency key retention/namespace policy and transaction isolation under the actual database configuration.
- Run load, failover, backup/restore, security and chaos tests in a non-production environment.
