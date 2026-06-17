# Local Development Environment

Run the full Payment Fraud Detection stack locally using LocalStack.

## Quick Start

```bash
./localstack/start-local.sh
```

This single command handles everything:
1. Checks prerequisites (Java 17+, Python 3, Docker, AWS CLI)
2. Installs Docker if missing (Ubuntu/Debian only)
3. Builds the Lambda deployment zip via Gradle
4. Starts LocalStack in Docker
5. Creates DynamoDB table (`FraudDetection`) with GSI
6. Creates EventBridge bus (`fraud-detection`)
7. Deploys the `FraudDetectionHandler` Lambda (Java 17, 512MB)
8. Creates API Gateway with `/fraud-check` and `/confirm-payment` routes
9. Runs a smoke test against the deployed API
10. Starts the development server at http://localhost:8080

## Stopping

```bash
./localstack/stop-local.sh
```

Or: `Ctrl+C` to stop the dev server, then `docker stop localstack`.

## Architecture

```
Browser (http://localhost:8080)
    │
    ├── GET /* ──────────────────► Python dev server (serves frontend/ files)
    │
    ├── POST /fraud-check ──────► Proxy ──► LocalStack API Gateway ──► Lambda
    │
    └── POST /confirm-payment ──► Stub response (backend not yet implemented)
```

The dev server (`serve.py`) acts as a reverse proxy that:
- Serves static frontend files from `frontend/`
- Proxies `/fraud-check` requests to the LocalStack API Gateway
- Stubs `/confirm-payment` with a success response (no backend handler exists yet)
- Adds CORS headers to all responses

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 17+ | Build the Lambda zip |
| Python | 3.x | Run the dev server |
| Docker | Any | Run LocalStack |
| AWS CLI | v2 | Deploy resources to LocalStack |
| Gradle | (wrapper) | Build system (included in project) |

## Configuration

Environment variables (all optional):

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | Dev server port |
| `LOCALSTACK_API_URL` | Auto-detected | Override LocalStack API URL |

## File Overview

| File | Purpose |
|------|---------|
| `start-local.sh` | Complete setup from scratch |
| `stop-local.sh` | Tear down the local environment |
| `deploy.sh` | Deploy AWS resources only (assumes LocalStack is running) |
| `seed-data.sh` | Populate DynamoDB with 100 test profiles |
| `serve.py` | Dev server with API proxy |

## Troubleshooting

### First Lambda call is slow
Java 17 Lambdas have a cold start of 5-15 seconds on LocalStack. Subsequent calls are fast.

### "Unable to connect" in the browser
The dev server proxy handles CORS. Make sure the frontend `data-api-base-url` is set to `""` (empty string) in `index.html` so API calls go through the same-origin proxy.

### Docker permission denied
Run `sudo usermod -aG docker $USER` and log out/in, or prefix docker commands with `sudo`.

### Port 8080 already in use
Set a different port: `PORT=9090 ./localstack/start-local.sh`

### LocalStack container won't start
Check if the port is in use: `lsof -i :4566`
Check Docker logs: `docker logs localstack`

## Test Data

Run `./localstack/seed-data.sh` to populate DynamoDB with 100 test records (80 customer profiles + 20 beneficiary entries). This is also called automatically by `start-local.sh` if you uncomment the seed line.

### Test Accounts

| Type | Sort Code | Account Number | Profile |
|------|-----------|----------------|---------|
| Debtor (low-risk) | `10-10-10` | `10000001` – `10000020` | High tx count (80-200), low mean (£200-£500) |
| Debtor (medium-risk) | `20-20-20` | `20000001` – `20000020` | Moderate tx count (20-60), medium mean (£500-£1500) |
| Debtor (high-value) | `30-30-30` | `30000001` – `30000020` | High tx count (50-150), high mean (£5000-£15000) |
| Debtor (new customer) | `40-40-40` | `40000001` – `40000020` | Very low tx count (1-5), low mean (£100-£300) |
| Creditor (clean) | `50-50-50` | `50000001` – `50000010` | Flag: NONE |
| Creditor (high-risk) | `60-60-60` | `60000001` – `60000006` | Flag: HIGH_RISK |
| Creditor (mule-linked) | `70-70-70` | `70000001` – `70000004` | Flag: MULE_LINKED |

### Example Scenarios

| Scenario | Debtor | Creditor | Amount | CoP | Expected |
|----------|--------|----------|--------|-----|----------|
| Routine payment | `10-10-10` / `10000001` | `50-50-50` / `50000001` | £150 | MATCH | ALLOW |
| Large unusual payment | `20-20-20` / `20000001` | `50-50-50` / `50000002` | £5000 | MATCH | REVIEW |
| New customer, risky creditor | `40-40-40` / `40000001` | `60-60-60` / `60000001` | £9500 | NO_MATCH | REVIEW/BLOCK |
| High-value customer, normal payment | `30-30-30` / `30000001` | `50-50-50` / `50000003` | £8000 | MATCH | ALLOW |

### Seeding Manually

```bash
# Seed after LocalStack is running
./localstack/seed-data.sh

# Verify item count
AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
  aws --endpoint-url=http://localhost:4566 --region us-east-1 \
  dynamodb scan --table-name FraudDetection --select COUNT
```
