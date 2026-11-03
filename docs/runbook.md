# Local runbook

## Check services

```bash
docker compose ps
docker compose logs -f wallet-service
docker compose logs -f kyc-statement-service
docker compose logs -f kafka
```

## Common problems

- **Database migration/connection failure:** wait for both MySQL health checks; verify `.env` usernames/passwords and inspect `docker compose logs mysql-wallet mysql-kyc`.
- **Kafka connection failure:** confirm the app containers use `kafka:9092`; host-side processes should use `localhost:29092`.
- **Redis unavailable:** wallet transfers return 503 for the rate limiter because it fails closed. Start Redis and inspect `docker compose logs redis`.
- **Presigned upload URL not reachable from the host:** check `APP_S3_PRESIGN_ENDPOINT=http://localhost:9000`, the MinIO port mapping and the returned `Content-Type` header.
- **KYC review says file is missing:** the upload request must be a direct PUT to the presigned URL, with the same signed `Content-Type` value.
- **Statement is FAILED:** inspect the job `errorMessage` and KYC service logs. The service fails rather than silently exporting a partial transaction projection if Kafka consumption lags behind.
- **Kafka DLT:** inspect topic names containing `.DLT`, the KYC service log, and the source event/outbox row. Replay only after fixing the root cause.

## Reset only the local development data

This deletes local container volumes and all demo data:

```bash
docker compose down -v
docker compose up --build -d
```

Do not run this against any environment containing data that matters.
