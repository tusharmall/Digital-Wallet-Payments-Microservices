# Local-first run guide (Windows PowerShell)

## What you need right now

- Docker Desktop, running with Linux containers enabled.
- No Kubernetes, Helm, GitHub self-hosted runner/controller, JFrog, AWS account or real S3 bucket is needed for the local run.
- Docker Compose starts local MySQL databases, Redis, Kafka, MinIO (S3-compatible object storage), Mailpit and the two Spring Boot services.

The GitHub Actions CI workflow uses `runs-on: ubuntu-latest`, which is a GitHub-hosted runner. The JFrog image-publishing workflow has been disabled in this local-first copy because no JFrog registry or secrets are configured.

## 1. Verify Docker

Open Docker Desktop and wait until its engine has started. In PowerShell:

```powershell
docker version
docker compose version
```

If either command fails, fix Docker Desktop before continuing.

## 2. Start the complete local application

Open PowerShell in the extracted `digital-wallet-payments` project folder:

```powershell
Copy-Item .env.example .env
docker compose config
docker compose up --build -d
docker compose ps
```

The first startup downloads images and builds both Java services, so it can take a while. Do not run the commands from a parent folder; run them from the folder containing `docker-compose.yml`.

Check service startup logs:

```powershell
docker compose logs --tail=120 wallet-service
docker compose logs --tail=120 kyc-statement-service
```

Check both health endpoints:

```powershell
Invoke-RestMethod http://localhost:8081/actuator/health
Invoke-RestMethod http://localhost:8082/actuator/health
```

Both responses should show `status: UP`.

## 3. Run the core wallet smoke test

After both health endpoints return `UP`, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1
```

The script creates two demo users, funds one wallet with a simulated INR 1,000, transfers INR 125.50, retries the exact same transfer with the same idempotency key, and verifies that the retry did not move money a second time. It checks the final balances as well.

Expected final balances for the newly created users:

- Sender: INR 874.50
- Receiver: INR 125.50

## 4. Run the automated integration tests

The project test suite uses Testcontainers to launch real MySQL, Kafka and Redis containers. Docker Desktop must be running. From the project root:

```powershell
mvn -B clean verify
```

If your machine is low on memory, stop the running Compose stack first to avoid running two sets of dependencies at the same time. `docker compose down` preserves named volumes; `docker compose down -v` deletes local database, Redis and MinIO data.

## 5. Useful local consoles

- Wallet health: http://localhost:8081/actuator/health
- KYC/statement health: http://localhost:8082/actuator/health
- MinIO S3 console: http://localhost:9001
- Mailpit inbox: http://localhost:8025

Local MinIO credentials are in `.env`. The presigned URLs generated for a browser on your machine use `http://localhost:9000`.

## 6. Inspect problems before changing configuration

```powershell
docker compose ps
docker compose logs --tail=200 wallet-service
docker compose logs --tail=200 kyc-statement-service
docker compose logs --tail=200 kafka
docker compose logs --tail=200 mysql-wallet
docker compose logs --tail=200 mysql-kyc
docker compose logs --tail=200 minio
```

Do not delete data volumes as a first troubleshooting step. If the application fails, keep the first error message and the logs above.

## 7. What is deliberately postponed

- **Helm/Kubernetes:** use only after the application passes local tests and you decide to deploy into an accessible Kubernetes cluster. The chart deploys the two app services; it assumes that MySQL, Kafka, Redis, S3, SMTP and Kubernetes Secrets already exist.
- **GitHub Actions:** CI can run on GitHub-hosted runners without a self-hosted runner or Actions Runner Controller. A custom runner/controller is relevant only if you explicitly choose self-hosted runners.
- **JFrog:** the publishing workflow stays disabled until you have a registry and its credentials. Locally, Docker Compose builds the images directly.
- **AWS S3:** local tests use MinIO. AWS S3 is a later deployment choice.
