# Real-Time Transaction Risk Engine with Explainable AI

An event-driven fraud-detection pipeline built with **Spring Boot**, **Kafka**, **Redis**, and **Mistral AI**. The engine combines deterministic velocity-rule checks with AI-powered reasoning to score and explain financial transactions in real time.

---

## Table of Contents

- [Architecture](#architecture)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Run with Docker Compose](#run-with-docker-compose)
  - [Manual Setup (without Docker)](#manual-setup-without-docker)
- [API Reference](#api-reference)
- [How It Works](#how-it-works)
  - [1. Transaction Ingestion](#1-transaction-ingestion)
  - [2. Redis Velocity Check](#2-redis-velocity-check)
  - [3. Rule Engine](#3-rule-engine)
  - [4. AI Assessment](#4-ai-assessment)
  - [5. Outbox Pattern & Exactly-Once Delivery](#5-outbox-pattern--exactly-once-delivery)
- [Configuration](#configuration)
- [Testing](#testing)
- [Simulation](#simulation)
- [Project Structure](#project-structure)
- [License](#license)

---

## Architecture

```
┌─────────────────┐     Kafka      ┌──────────────────────────────────────┐
│  Client / App   │ ──────────▶    │         Risk Scoring Consumer        │
│  POST /api/...  │   txn.incoming │  (idempotent, async processing)      │
└─────────────────┘                └──────────────────────┬───────────────┘
                                                          │
                                          ┌───────────────┼──────────────┐
                                          ▼               ▼              ▼
                                    ┌──────────┐   ┌──────────┐   ┌──────────┐
                                    │  Redis   │   │  Rule    │   │ Mistral  │
                                    │ Velocity │──▶│ Engine   │──▶│    AI    │
                                    │  Check   │   │ (0-100)  │   │ (if flagged)
                                    └──────────┘   └──────────┘   └──────────┘
                                                          │              │
                                          ┌───────────────┼──────────────┘
                                          ▼               ▼
                                    ┌──────────────────────────┐
                                    │      MySQL (Audit DB)    │
                                    │  risk_decisions table    │
                                    │  outbox_events table     │
                                    └────────────┬─────────────┘
                                                 │
                                    Outbox Publisher (polling every 2s)
                                                 │
                                              Kafka
                                           txn.decision
```

---

## Features

| Feature | Description |
|---|---|
| **⚡ Real-Time Processing** | Event-driven pipeline via Kafka with 3 partitions for parallel consumption |
| **🔍 Velocity Checks** | Redis-backed sliding-window rate limiter across user, device, and merchant dimensions |
| **📐 Deterministic Rule Engine** | Configurable thresholds for amount, time-of-day, and velocity patterns |
| **🤖 Explainable AI** | Mistral AI integration generates structured risk reports with confidence score and rationale |
| **🛡️ Graceful Degradation** | Resilience4j circuit breaker automatically falls back to rule-only scoring when AI is unavailable |
| **✅ Exactly-Once Semantics** | Outbox pattern + idempotency keys guarantee no duplicate decisions |
| **📝 Compliance Audit Trail** | Full MySQL audit log capturing every risk decision and AI reasoning snapshot |
| **🐳 Containerized** | Docker Compose provisions all dependencies (MySQL, Redis, Kafka, Zookeeper) |

---

## Tech Stack

| Component | Technology |
|---|---|
| **Language** | Java 17 |
| **Framework** | Spring Boot 3.2.1 |
| **Messaging** | Apache Kafka (3 partitions) |
| **Cache / Rate Limiter** | Redis 7 (ZSET sliding window) |
| **Database** | MySQL 8.0 |
| **AI Provider** | Mistral AI API (mistral-small-latest) |
| **Resilience** | Resilience4j Circuit Breaker |
| **Serialization** | Jackson, Spring Kafka JSON |
| **Containerization** | Docker, Docker Compose |
| **Testing** | JUnit 5, Mockito |
| **Build Tool** | Maven |

---

## Getting Started

### Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (recommended)
- Java 17+ and Maven (for manual/development setup)
- [Mistral AI API Key](https://console.mistral.ai/api-keys/) (set as `MISTRAL_API_KEY` environment variable)

### Run with Docker Compose

```bash
# 1. Clone the repository
git clone https://github.com/ashika0124/transaction-risk-engine.git
cd transaction-risk-engine

# 2. Set your Mistral API key
export MISTRAL_API_KEY=your-actual-mistral-api-key

# 3. Start all services
docker compose up -d

# 4. Verify health
curl http://localhost:8080/api/health

# 5. Simulate transactions
pip install requests
python scripts/simulate-transactions.py --total-transactions 50
```

### Manual Setup (without Docker)

Start MySQL, Redis, and Kafka locally, then:

```bash
# Build the application
mvn clean package -DskipTests

# Run with local profile (default)
MISTRAL_API_KEY=your-key java -jar target/transaction-risk-engine-1.0.0.jar

# Or activate Docker config profile manually:
MISTRAL_API_KEY=your-key java -jar target/transaction-risk-engine-1.0.0.jar --spring.profiles.active=docker
```

---

## API Reference

### `POST /api/transactions`

Ingest a transaction for risk assessment.

**Request Body:**
```json
{
  "transactionId": "txn-001",
  "userId": "user-abc123",
  "merchantId": "merchant-xyz789",
  "deviceId": "device-def456",
  "amount": 15000.00,
  "timestamp": "2026-07-12T14:30:00"
}
```

**Response (202 Accepted):**
```json
{
  "status": "accepted",
  "transactionId": "txn-001",
  "message": "Transaction received and queued for processing"
}
```

### `GET /api/health`

Health check endpoint.

**Response:**
```json
{
  "status": "UP",
  "service": "transaction-risk-engine"
}
```

### Decision Output (Kafka `txn.decision` topic)

After processing, the decision is published to the `txn.decision` topic:

```json
{
  "transactionId": "txn-001",
  "userId": "user-abc123",
  "finalAction": "REVIEW",
  "ruleScore": 55,
  "aiRiskScore": 62,
  "aiRationale": "High velocity from user (8 transactions in 2 min) + large amount ($15,000) suggests potential account compromise.",
  "decisionSource": "AI",
  "timestamp": "2026-07-12T14:30:05.123"
}
```

---

## How It Works

### 1. Transaction Ingestion

`POST /api/transactions` accepts a transaction, converts it to a `TransactionEvent`, and publishes it to the `txn.incoming` Kafka topic. The response returns immediately with `202 Accepted` — processing happens asynchronously.

### 2. Redis Velocity Check

`VelocityCheckService` maintains a **sliding-window counter** in Redis using **Sorted Sets (ZSET)** with timestamps as scores:

- **User dimension**: tracks transactions per user within a configurable window (default: 5 txns / 120s)
- **Device dimension**: tracks transactions per device (default: 10 txns / 120s)
- **Merchant dimension**: tracks transactions per merchant (default: 15 txns / 120s)

Operations are O(log N) for near-instant checks. A composite velocity score (0–100) is produced.

### 3. Rule Engine

`RuleEngineService` applies deterministic rules:

| Rule | Contribution | Threshold |
|---|---|---|
| User velocity exceeded | +30 points | > 5 txns in window |
| Device velocity exceeded | +20 points | > 10 txns in window |
| Merchant velocity exceeded | +15 points | > 15 txns in window |
| High transaction amount | +25 points | > $10,000 |
| Unusual time (midnight–6 AM) | +10 points | configurable window |

**If score ≥ 30**: the transaction is escalated for AI review.
**If score < 30**: the transaction is auto-approved with a "LOW_RISK" determination.

### 4. AI Assessment

`MistralRiskClient` calls the Mistral AI API with a structured prompt containing:
- Transaction details (ID, user, merchant, device, amount, time)
- Velocity check results
- Rule engine findings

The AI responds with **JSON** containing:
- `risk_score` (0–100)
- `rationale` (detailed explanation)
- `recommended_action` (ALLOW / REVIEW / BLOCK)
- `risk_factors` (comma-separated list)

**Resilience4j Circuit Breaker** protects the AI call:
- 10-call sliding window
- 50% failure threshold opens the circuit
- 30-second cooldown before half-open retry
- Falls back to **rule-only scoring** when AI is unavailable

### 5. Outbox Pattern & Exactly-Once Delivery

```
┌───────────────────────┐
│  1 DB Transaction     │
│  ┌─────────────────┐  │
│  │ risk_decisions  │  │  ← Decision + AI reasoning
│  └─────────────────┘  │
│  ┌─────────────────┐  │
│  │ outbox_events   │  │  ← Outbox row (published=false)
│  └─────────────────┘  │
└───────────────────────┘
          │
          ▼  (OutboxPublisher polls every 2s)
┌───────────────────────┐
│  Kafka txn.decision   │  ← Published and marked as published=true
└───────────────────────┘
```

- Decision and outbox row are written in the same database transaction
- If Kafka publish fails, the row stays unpublished and is retried
- Idempotency check (`existsByTransactionId`) prevents duplicate processing on redelivery

---

## Configuration

All configuration is in `src/main/resources/application.yml`:

```yaml
# Velocity checks
risk.engine.velocity.window-seconds: 120
risk.engine.velocity.user-threshold: 5
risk.engine.velocity.device-threshold: 10
risk.engine.velocity.merchant-threshold: 15

# Rule engine
risk.engine.rule.high-amount-threshold: 10000
risk.engine.rule.unusual-time-enabled: true
risk.engine.rule.unusual-time-start-hour: 0
risk.engine.rule.unusual-time-end-hour: 6

# AI circuit breaker
resilience4j.circuitbreaker.instances.mistralAI:
  sliding-window-size: 10
  failure-rate-threshold: 50
  wait-duration-in-open-state: 30s

# AI
mistral.api.url: https://api.mistral.ai/v1/chat/completions
mistral.api.model: mistral-small-latest
```

You can override any property via environment variables or `application-docker.yml` profile.

---

## Testing

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=RuleEngineServiceTest
```

The test suite covers:

| Test Class | Tests | Validates |
|---|---|---|
| `RuleEngineServiceTest` | 6 | Normal flow, velocity flags, high amount, unusual time, score capping, combined factors |
| `MistralRiskClientFallbackTest` | 3 | Fallback returns BLOCK/REVIEW/ALLOW based on score |

---

## Simulation

The `scripts/simulate-transactions.py` script generates synthetic transactions with configurable traffic patterns:

```bash
# Basic simulation (100 transactions)
python scripts/simulate-transactions.py

# Custom parameters
python scripts/simulate-transactions.py \
  --total-transactions 200 \
  --spike-probability 0.25 \
  --delay-min 0.05 \
  --delay-max 0.2

# Point to a different host
python scripts/simulate-transactions.py --base-url http://localhost:8080
```

The script periodically enters **"spike mode"** — concentrating transactions on the same users to trigger velocity-based risk flags.

---

## Project Structure

```
├── Dockerfile                        # Multi-stage Docker build
├── docker-compose.yml                # Infrastructure orchestration
├── pom.xml                           # Maven dependencies
├── scripts/
│   └── simulate-transactions.py      # Load testing script
├── src/
│   ├── main/
│   │   ├── java/com/riskengine/
│   │   │   ├── TransactionRiskEngineApplication.java
│   │   │   ├── config/
│   │   │   │   ├── KafkaConfig.java            # Topics + JSON converter
│   │   │   │   ├── KafkaConsumerConfig.java    # Consumer factory
│   │   │   │   └── RedisConfig.java            # Redis template
│   │   │   ├── controller/
│   │   │   │   └── TransactionController.java  # REST API
│   │   │   ├── dto/
│   │   │   │   ├── TransactionRequest.java
│   │   │   │   ├── TransactionEvent.java
│   │   │   │   ├── DecisionEvent.java
│   │   │   │   └── RiskAssessment.java
│   │   │   ├── entity/
│   │   │   │   ├── RiskDecision.java           # Audit table
│   │   │   │   └── OutboxEvent.java            # Outbox table
│   │   │   ├── repository/
│   │   │   │   ├── RiskDecisionRepository.java
│   │   │   │   └── OutboxEventRepository.java
│   │   │   └── service/
│   │   │       ├── VelocityCheckService.java   # Redis rate limiter
│   │   │       ├── VelocityResult.java
│   │   │       ├── RuleEngineService.java      # Deterministic rules
│   │   │       ├── RuleResult.java
│   │   │       ├── MistralRiskClient.java      # AI client + circuit breaker
│   │   │       ├── RiskScoringConsumer.java    # Main pipeline consumer
│   │   │       └── OutboxPublisher.java        # Outbox poller
│   │   └── resources/
│   │       └── application.yml                 # Configuration
│   └── test/java/com/riskengine/service/
│       ├── RuleEngineServiceTest.java
│       └── MistralRiskClientFallbackTest.java
```

---

## License

This project is open source and available under the [MIT License](LICENSE).

---

Built with ☕ and Spring Boot.