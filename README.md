# Event-Driven Notification System

A production-grade, event-driven notification system that guarantees non-blocking APIs, exactly-once delivery, safe retries, and dead-letter handling for permanent failures.

---

## Problem Statement

Synchronous notification delivery causes critical production issues:

- **API latency & failures** — Providers (email, SMS) can be slow or unreachable; blocking calls tie up threads
- **Duplicate notifications** — Network retries or client retries cause users to receive the same notification multiple times
- **Unsafe retries** — Naive retry logic can spam users during provider outages
- **Silent message loss** — When providers fail, messages are dropped with no audit trail or recovery path

This system solves these by decoupling API acceptance from delivery, using durable queues, idempotency, and explicit failure handling.

---

## System Architecture

### Components & Responsibilities

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   API Service   │     │  Redis Streams  │     │ Worker Service  │
│  (Spring Boot)  │────▶│  (Queue)        │────▶│ (Spring Boot)   │
│                 │     │                 │     │                 │
│ • Accept req    │     │ • Durable       │     │ • Consume msgs  │
│ • Persist       │     │ • Consumer      │     │ • Send via      │
│ • Publish       │     │   groups        │     │   provider      │
│ • Idempotency   │     │ • At-least-once │     │ • Update state  │
│ • Rate limit    │     │   delivery      │     │ • Retry/DLQ     │
└────────┬────────┘     └─────────────────┘     └────────┬────────┘
         │                                                      │
         │               ┌─────────────────┐                    │
         └──────────────▶│   PostgreSQL    │◀───────────────────┘
                         │                 │
                         │ • notifications │
                         │ • dead_letter   │
                         └─────────────────┘
```

| Component | Responsibility |
|-----------|----------------|
| **API Service** | Accepts requests, enforces idempotency, rate limits, persists PENDING notifications, publishes to Redis Stream, returns 202 Accepted |
| **Redis Streams** | Durable queue with consumer groups; enables at-least-once delivery to workers; survives restarts |
| **Worker Service** | Consumes events, claims messages via XREADGROUP, processes with locking, updates DB state, retries or DLQs on failure |
| **PostgreSQL** | Source of truth for notification state; unique constraint on idempotency key; DLQ audit trail |

### Data Flow: API → Queue → Worker → Delivery

1. **Client** sends `POST /notifications/send` with `userId`, `channel`, `payload`, `idempotencyKey`
2. **API** checks idempotency: if key exists → return cached 202 + notification ID
3. **API** checks rate limit (Redis): if exceeded → return 429
4. **API** inserts row in `notifications` with state `PENDING`, returns ID
5. **API** publishes event to Redis Stream `notifications:stream`
6. **API** returns 202 Accepted with notification ID
7. **Worker** uses XREADGROUP to claim messages; processes one at a time per consumer
8. **Worker** transitions to `PROCESSING`, calls mock provider
9. **Provider success** → transition to `SENT`
10. **Provider failure (transient)** → transition to `RETRYING`, re-add to stream with backoff, or use Redis pending list for retry
11. **Provider failure (permanent)** / max retries → insert into `dead_letter_notifications`, transition to `FAILED`

### Failure Handling

| Failure Point | Handling |
|---------------|----------|
| API down | Client retries; idempotency prevents duplicates |
| Redis down | API fails fast; client retries |
| Worker down | Messages remain in stream; another worker or restarted worker consumes |
| Provider timeout | Worker retries with exponential backoff; after max retries → DLQ |
| Provider 4xx/permanent error | Immediate DLQ, no retry |
| DB write failure | API rolls back; no event published; client can retry |
| Duplicate idempotency key | Return existing notification; no new row, no new event |

---

## State Machine

```
                    ┌─────────────┐
                    │   PENDING   │  (Initial state after API accepts)
                    └──────┬──────┘
                           │ Worker picks up
                           ▼
                    ┌─────────────┐
                    │ PROCESSING  │  (Worker has claimed, sending)
                    └──────┬──────┘
                           │
           ┌───────────────┼───────────────┐
           │               │               │
           ▼               ▼               ▼
    ┌──────────┐   ┌──────────┐   ┌──────────┐
    │   SENT   │   │ RETRYING │   │  FAILED  │
    └──────────┘   └────┬─────┘   └──────────┘
                        │              ▲
                        │ max retries  │ permanent error
                        │ exceeded     │ or give-up
                        └──────────────┘
```

| State | Description |
|-------|-------------|
| **PENDING** | Notification accepted by API; event published or queued; not yet claimed by worker |
| **PROCESSING** | Worker has claimed the message; delivery in progress |
| **SENT** | Successfully delivered |
| **RETRYING** | Transient failure; will retry with backoff |
| **FAILED** | Permanent failure or max retries exceeded; moved to DLQ |

### Transitions

- `PENDING` → `PROCESSING`: Worker starts processing
- `PROCESSING` → `SENT`: Delivery success
- `PROCESSING` → `RETRYING`: Transient failure (timeout, 5xx)
- `RETRYING` → `PROCESSING`: Retry attempt
- `RETRYING` → `FAILED`: Max retries exceeded
- `PROCESSING` → `FAILED`: Permanent error (4xx, invalid config)

---

## Exactly-Once Delivery

### Idempotency Keys

- Client supplies `idempotencyKey` (UUID or unique string) per logical notification
- Stored in `notifications.idempotency_key` with **UNIQUE** constraint
- On duplicate key: return existing notification metadata (202 + same ID); no new insert, no new stream event

### Database Uniqueness

```sql
UNIQUE (idempotency_key)
```

- Prevents duplicate rows at DB level
- API uses `INSERT ... ON CONFLICT DO NOTHING` or `findByIdempotencyKey` + conditional insert

### Duplicate API Calls

1. First call: insert row, publish event, return 202
2. Second call (same idempotency key): unique constraint prevents insert; API detects conflict, returns 202 with existing ID
3. No second event published → exactly one event per logical notification
4. Worker processes event once → exactly-once delivery per event

---

## Retry Strategy

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Max retries | 5 | Balance between recovery and avoiding infinite loops |
| Backoff | Exponential: 1s, 2s, 4s, 8s, 16s | Reduces load during provider outages |
| Retry on | Timeout, 5xx, connection errors | Transient failures |
| No retry (→ DLQ) | 4xx, invalid payload, max retries exceeded | Permanent failures |

### Retry vs Dead-Letter

- **Retry**: Transient errors (timeout, 503, connection reset)
- **Dead-Letter**: Permanent errors (400, 404, invalid config) or retry count exceeded

---

## Rate Limiting

### Why Required

- Prevents abuse (one user flooding the system)
- Protects downstream providers (email/SMS) from quota exhaustion
- Protects infrastructure from overload

### Implementation

- **Redis-based counters**: `rate_limit:{userId}` with TTL (e.g. 1 minute)
- **Limit**: e.g. 10 notifications per user per minute (configurable)
- **Behavior**: If exceeded, return 429 Too Many Requests
- **Scope**: Per-user, per channel optional

---

## Guarantees

- **Non-blocking API**: Returns 202 immediately; delivery is asynchronous
- **Exactly-once per event**: Idempotency key + unique constraint + single event per key
- **At-least-once from queue**: Redis Streams + consumer groups; worker must be idempotent (state updates are idempotent)
- **Safe retries**: Exponential backoff, max attempts, DLQ for permanent failures
- **Audit trail**: All failed notifications in `dead_letter_notifications`
- **Horizontal scalability**: Multiple API instances, multiple workers; Redis consumer groups distribute load

---

## Tech Stack

- **API & Worker**: Spring Boot 3.x (Java 17)
- **Queue**: Redis Streams
- **Database**: PostgreSQL
- **Build**: Maven
- **Containerization**: Docker, Docker Compose

---

## Project Structure

```
NotificationService/
├── pom.xml                    # Parent POM
├── shared/                    # Shared domain, entities, DTOs
│   └── pom.xml
├── api-service/               # REST API
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/.../controller, service, repository, config
├── worker-service/            # Stream consumer & delivery
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/.../worker, service, repository, config
├── docker-compose.yml
└── README.md
```

---

## How to Run

### Prerequisites

- Docker & Docker Compose
- (Optional) Java 17, Maven for local development

### Using Docker Compose

```bash
docker-compose up -d
```

This starts:

- **PostgreSQL** on port 5432
- **Redis** on port 6379
- **API Service** on port 8080
- **Worker Service** (no exposed port; consumes from Redis)

### Health Checks

- API: `GET http://localhost:8080/actuator/health`
- PostgreSQL and Redis are used internally; no separate health HTTP endpoints

### Send a Notification

```bash
curl -X POST http://localhost:8080/notifications/send \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "userId": "user-123",
    "channel": "EMAIL",
    "payload": {
      "to": "user@example.com",
      "subject": "Welcome",
      "body": "Hello!"
    }
  }'
```

Expected: `202 Accepted` with notification ID.

### Idempotency Test

Send the same request twice with the same `Idempotency-Key`. Both return 202 with the same notification ID; only one event is processed.

---

## Configuration

Environment variables (see `docker-compose.yml` and `application.yml`):

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | jdbc:postgresql://postgres:5432/notifications | DB URL |
| `SPRING_REDIS_HOST` | redis | Redis host |
| `SPRING_REDIS_PORT` | 6379 | Redis port |
| `NOTIFICATION_MAX_RETRIES` | 5 | Max delivery retries |
| `RATE_LIMIT_PER_USER_PER_MINUTE` | 10 | Notifications per user per minute |

---

## Observability

- **Correlation ID**: Passed via `X-Correlation-ID` header; propagated to logs
- **Structured logging**: JSON logs with correlation ID, notification ID, state transitions
- **Retry logs**: Log each retry attempt with attempt number and delay
- **DLQ logs**: Log when notifications are moved to dead-letter queue
- **Failure logs**: Log provider errors with full context
