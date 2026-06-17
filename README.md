# Payment Fraud Detection

A real-time fraud scoring system for UK Faster Payments, built as a serverless Java 17 application on AWS. It evaluates payment requests against customer behavioural profiles, beneficiary risk registries, IP intelligence, and payment purpose analysis to produce ALLOW, REVIEW, or BLOCK decisions within a 300ms SLA.

## Architecture

```
┌─────────────┐      ┌─────────────────┐      ┌──────────────────────┐
│  Frontend   │─────▶│  API Gateway    │─────▶│ FraudDetectionHandler│
│  (HTML/JS)  │      │ /fraud-check    │      │    (Lambda, Java 17) │
└─────────────┘      │ /confirm-payment│      └──────────┬───────────┘
                     └─────────────────┘                 │
                                                         │ EventBridge
                                                         │ (DecisionMade)
                                              ┌──────────┴───────────┐
                                              │                      │
                                    ┌─────────▼──────┐    ┌─────────▼──────────┐
                                    │ AuditLogHandler │    │ProfileUpdateHandler│
                                    │   (Lambda)      │    │     (Lambda)       │
                                    └────────┬────────┘    └────────┬───────────┘
                                             │                      │
                                    ┌────────▼────────┐    ┌────────▼───────────┐
                                    │ DynamoDB + S3   │    │     DynamoDB       │
                                    │ (audit archive) │    │ (customer profiles)│
                                    └─────────────────┘    └────────────────────┘
```

**AWS Services:** Lambda, DynamoDB (single-table), EventBridge, API Gateway, S3, SQS

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 17, AWS SDK v2, Jackson |
| Frontend | Vanilla HTML/CSS/JS (no framework) |
| Data | DynamoDB (single-table design with GSI) |
| Events | EventBridge with SQS bridge |
| Build | Gradle |
| Testing | JUnit 5, jqwik (property-based), Mockito, Testcontainers |
| Local Dev | LocalStack (Docker), Python dev server |

## How It Works

### Fraud Scoring Pipeline

When a payment request arrives at `/fraud-check`:

1. **Validate** — Sort codes, account numbers, amount range, required fields
2. **Load context** — Customer profile (DynamoDB) + beneficiary status (DynamoDB)
3. **Score risk** — Six independent scorers run within a 280ms timeout:

| Scorer | What it evaluates | Score range |
|--------|-------------------|-------------|
| **AmountScorer** | Deviation from historical mean (mean + 3σ threshold) | 0–25 |
| **CopScorer** | Confirmation of Payee result (MATCH/CLOSE_MATCH/NO_MATCH/NOT_AVAILABLE) | 0–22 |
| **BehaviouralScorer** | Session duration vs customer average (below 50% = suspicious) | 0–25 |
| **ChannelScorer** | Unknown device (+10), location >50km (+15), PHONE channel (+5) | 0–25 |
| **IpIntelligenceScorer** | VPN/proxy, TOR, high-risk geo, reputation, velocity, new IP | 0–25 |
| **PurposeScorer** | Scam keywords, purpose category, scam patterns, behavioural deviation | 0–25 |

4. **Decide** — Composite score (0–150) maps to a decision:
   - **0–30** → ALLOW
   - **31–70** → REVIEW
   - **71+** → BLOCK

5. **Apply overrides:**
   - Beneficiary flagged `MULE_LINKED` → always BLOCK
   - Beneficiary flagged `HIGH_RISK` → minimum score 71 (BLOCK)
   - TOR network detected → always BLOCK
   - VPN + amount > £1000 → minimum REVIEW
   - Amount exceeds dynamic threshold → minimum REVIEW

6. **Publish event** — Fire-and-forget to EventBridge for audit and profile updates

### Event-Driven Downstream

- **AuditLogHandler** — Persists every decision to DynamoDB and archives to S3 (with 3-retry exponential backoff)
- **ProfileUpdateHandler** — Recalculates customer statistics using Welford's online algorithm (incremental mean/stddev)

## Feature: IP Intelligence (Iteration 1)

Detects network-layer fraud signals from the originating IP address.

**Signals scored:**

| Signal | Condition | Raw Score |
|--------|-----------|-----------|
| VPN/Proxy | `isVpn` or `isProxy` = true | +25 |
| TOR | `isTor` = true | +40 |
| High-risk geography | `isHighRiskGeo` = true | +20 |
| IP reputation | score > 70 | +30 |
| Velocity anomaly | multiple requests from same IP | +20 |
| New/unseen IP | not in customer history | +15 |

Raw score (max 150) is normalized to [0, 25].

**Decision overrides:**
- TOR usage → always BLOCK
- VPN + high-value payment (>£1000) → minimum REVIEW

## Feature: Purpose of Payment Intelligence (Iteration 2)

Detects social engineering scams and misleading payment narratives.

**Signals scored:**

| Signal | Condition | Raw Score |
|--------|-----------|-----------|
| Scam keywords | "crypto", "urgent", "investment", "guaranteed", "hmrc", etc. | +30 |
| High-risk category | INVESTMENT or UNKNOWN | +25 |
| Known scam pattern | INVESTMENT_SCAM, ROMANCE_SCAM, IMPERSONATION, INVOICE_REDIRECTION | +50 |
| Behavioural deviation | First-time payment pattern | +25 |
| Low confidence | Classification confidence < 0.5 | +10 |

Raw score (max 140) is normalized to [0, 25].

**Scam keywords detected in payment reference:**
`crypto`, `bitcoin`, `urgent`, `investment`, `guaranteed`, `returns`, `profit`, `hmrc`, `tax refund`, `prize`, `lottery`, `inheritance`, `wire transfer`, `act now`, `limited time`, `safe account`, `holding account`

## Project Structure

```
├── frontend/                    # Browser UI
│   ├── index.html               # Payment form (all scoring parameters)
│   ├── app.js                   # App controller (wires form → API → results)
│   ├── api.js                   # HTTP client with 10s timeout
│   ├── form.js                  # Form DOM management
│   ├── validation.js            # Client-side input validation
│   └── result.js                # Decision result rendering
├── src/main/java/com/frauddetection/
│   ├── handler/
│   │   ├── FraudDetectionHandler.java   # API Lambda (scoring orchestrator)
│   │   ├── AuditLogHandler.java         # Event consumer (DDB + S3 persistence)
│   │   └── ProfileUpdateHandler.java    # Event consumer (profile recalculation)
│   ├── scoring/
│   │   ├── RiskScoringEngine.java       # Composes all 6 scorers
│   │   ├── DecisionEngine.java          # Score → decision mapping + overrides
│   │   ├── AmountScorer.java            # Statistical amount analysis
│   │   ├── CopScorer.java              # Confirmation of Payee scoring
│   │   ├── BehaviouralScorer.java       # Session timing analysis
│   │   ├── ChannelScorer.java           # Device/location/channel risk
│   │   ├── IpIntelligenceScorer.java    # IP network risk scoring
│   │   ├── PurposeScorer.java           # Payment purpose & scam detection
│   │   └── ConcurrencyGuard.java        # Concurrency limiting
│   ├── model/                           # Java records (immutable data classes)
│   ├── repository/                      # DynamoDB data access layer
│   ├── validation/                      # Server-side request validation
│   └── config/                          # Jackson serialization config
├── localstack/
│   ├── start-local.sh           # One-command local setup
│   ├── deploy.sh                # AWS resource provisioning
│   ├── seed-data.sh             # Test data (80 profiles, 20 beneficiaries)
│   ├── serve.py                 # Dev server (static files + API proxy)
│   └── stop-local.sh            # Teardown
└── build.gradle                 # Dependencies and build config
```

## DynamoDB Single-Table Design

| Entity | PK | SK | GSI |
|--------|----|----|-----|
| Customer Profile | `CUST#{sortCode}#{accountNumber}` | `PROFILE` | — |
| Beneficiary Registry | `BENE#{sortCode}#{accountNumber}` | `STATUS` | — |
| Decision Audit | `AUDIT#{messageId}` | `DECISION` | `DecisionByDate` (gsiPk=`DECISION#{decision}`, gsiSk=timestamp) |

## Getting Started

### Prerequisites

- Java 17+
- Docker
- Python 3.x
- AWS CLI v2

### One-Command Setup

```bash
chmod +x localstack/start-local.sh
./localstack/start-local.sh
```

This will:
1. Check/install prerequisites
2. Build the Lambda deployment zip (`./gradlew buildLambdaZip`)
3. Start LocalStack in Docker
4. Deploy all AWS resources (DynamoDB, EventBridge, Lambda, API Gateway, S3, SQS)
5. Seed test data (80 customer profiles + 20 beneficiary records)
6. Run a smoke test
7. Start the dev server at **http://localhost:8080**

### Stop

```bash
./localstack/stop-local.sh
```

## Test Data

The seed script creates accounts across four risk tiers:

| Debtor Type | Sort Code | Account | Behaviour |
|-------------|-----------|---------|-----------|
| Low-risk | `10-10-10` | `10000001`–`10000020` | High tx count, low variance |
| Medium-risk | `20-20-20` | `20000001`–`20000020` | Moderate spend, fewer txns |
| High-value | `30-30-30` | `30000001`–`30000020` | Large amounts, regular pattern |
| New customer | `40-40-40` | `40000001`–`40000020` | 1–5 transactions (low history) |

| Creditor Type | Sort Code | Account | Flag |
|---------------|-----------|---------|------|
| Clean | `50-50-50` | `50000001`–`50000010` | NONE |
| High-risk | `60-60-60` | `60000001`–`60000006` | HIGH_RISK |
| Mule-linked | `70-70-70` | `70000001`–`70000004` | MULE_LINKED |

**Example scenarios to try:**
- Low-risk debtor + clean creditor + small amount → **ALLOW**
- New customer + high-risk creditor + large amount → **BLOCK**
- Medium-risk debtor + amount above threshold → **REVIEW**
- Any payment with TOR enabled → **BLOCK**
- Payment reference "crypto investment" → purpose score triggers
- VPN + £2000 payment → elevated to **REVIEW**

## API Reference

### POST /fraud-check

Evaluates a Faster Payment for fraud risk.

**Request:**
```json
{
  "messageId": "uuid",
  "debtorAccount": { "sortCode": "101010", "accountNumber": "10000001", "accountName": "John Smith" },
  "creditorAccount": { "sortCode": "505050", "accountNumber": "50000001", "accountName": "Jane Doe" },
  "amount": 250.00,
  "currency": "GBP",
  "paymentReference": "Invoice 123",
  "confirmationOfPayee": { "result": "MATCH", "matchedName": "Jane Doe" },
  "channel": {
    "type": "MOBILE",
    "deviceId": "device-001",
    "geoLocation": null,
    "sessionDuration": null,
    "ipIntelligence": {
      "ipAddress": "192.168.1.1",
      "country": "GB",
      "region": "London",
      "isVpn": false,
      "isProxy": false,
      "isTor": false,
      "ipReputationScore": 20,
      "isHighRiskGeo": false,
      "velocityFlag": false,
      "isNewIp": false,
      "lastSeenTimestamp": "2026-06-17T10:00:00Z"
    },
    "purposeAnalysis": {
      "declaredPurpose": "Invoice 123",
      "purposeCategory": "BILL_PAYMENT",
      "scamIndicator": "NONE",
      "confidenceScore": 0.95,
      "historicalDeviation": false
    }
  },
  "timestamp": "2026-06-17T10:00:00Z"
}
```

**Response:**
```json
{
  "messageId": "uuid",
  "decision": "ALLOW",
  "riskScore": 0,
  "breakdown": {
    "amountScore": 0,
    "copScore": 0,
    "behaviouralScore": 0,
    "channelScore": 0,
    "ipScore": 0,
    "purposeScore": 0
  },
  "riskFactors": [],
  "timestamp": "2026-06-17T10:00:00.123Z"
}
```

### POST /confirm-payment

Confirms a payment that received a REVIEW decision (step-up flow).

**Request:**
```json
{ "messageId": "uuid-from-fraud-check-response" }
```

## Build Commands

```bash
# Build the Lambda deployment zip
./gradlew buildLambdaZip

# Run unit + property-based tests
./gradlew test

# Run integration tests (requires Docker for Testcontainers)
./gradlew integrationTest

# Full build
./gradlew build
```

## Design Decisions

- **300ms SLA** — Scoring runs with a 280ms timeout; on timeout, defaults to REVIEW (safe fallback)
- **Single-table DynamoDB** — All entities share one table with PK/SK patterns for efficient access
- **Fire-and-forget events** — Decision publishing never blocks the response path
- **Welford's algorithm** — Incremental mean/stddev updates avoid reprocessing full transaction history
- **Concurrency guard** — Protects against burst traffic overwhelming downstream services
- **Beneficiary overrides** — Hard rules that bypass scoring (mule-linked accounts are always blocked)
- **Score normalization** — IP and Purpose scorers normalize raw signals to [0, 25] to maintain balance with other scorers
- **Keyword-first detection** — Purpose scorer scans payment reference for scam keywords even without explicit purpose analysis data
- **Graceful degradation** — Missing IP/purpose data results in 0 score (never blocks due to absent signals)

## Iteration History

| Iteration | Feature | Key Value |
|-----------|---------|-----------|
| Base | Core fraud scoring | Amount, CoP, behavioural, channel risk detection |
| 1 | IP Intelligence | Network-layer fraud detection (VPN, TOR, geo, velocity) |
| 2 | Purpose Analysis | Social engineering & scam detection (keywords, patterns, deviation) |
