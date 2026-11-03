# Existing-cluster deployment notes

This chart packages only two workloads and their internal ClusterIP services. MySQL, Kafka, Redis, S3, SMTP, Vault and the API Gateway are expected to already exist in the target cluster.

Before install:

1. Set JFrog image repository paths and image tags in `digital-wallet/values.yaml`.
2. Set Kafka and Redis service DNS names, S3 bucket/region and SMTP relay.
3. Provision `wallet-service-secrets` and `kyc-statement-service-secrets` from your existing Vault integration. Both must receive the same `INTERNAL_SERVICE_TOKEN`. Keep database passwords and access credentials out of `values.yaml` and Git.
4. Verify the Wallet and KYC service accounts can reach their database endpoints and Kafka, Redis, S3 and SMTP services.
5. Merge only the public API paths from `../../infra/gateway-routes.yml` into the existing gateway and add your actual JWT/session-to-header filter. Do not route `/internal/**` publicly.

Example:

```bash
helm upgrade --install wallet-app ./digital-wallet \
  --namespace staging --create-namespace \
  --set walletService.image.tag=<git-sha> \
  --set kycStatementService.image.tag=<git-sha>
```

Use separate values files and Vault roles for staging and production. The sample values are illustrative; endpoint names must match your cluster.
