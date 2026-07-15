# Event-Driven Notification Service Spec

Last updated: 2026-07-15

Use this file as the first reference for future work in this project. It summarizes the intended system, the current implementation, how the modules fit together, how to run it, and the important caveats discovered from the codebase.

## 1. Project Purpose

This repository implements an event-driven notification system that accepts notification requests through a REST API, stores them durably in PostgreSQL, publishes work to Redis Streams, and processes delivery asynchronously in a worker service.

The system is designed around these goals:

- Non-blocking API acceptance: the API returns `202 Accepted` without waiting for provider delivery.
- Idempotent request handling: duplicate logical requests reuse the same notification row.
- Durable asynchronous delivery: Redis Streams carries work from API to worker.
- Stateful delivery tracking: PostgreSQL is the source of truth for notification state.
- Controlled retries and failure audit: transient failures update retry state; permanent failures go to a dead-letter table.
- Horizontal scalability: API and worker services can be scaled independently.

## 2. Repository Layout

```text
.
|-- pom.xml
|-- docker-compose.yml
|-- README.md
|-- spec.md
|-- shared/
|   |-- pom.xml
|   `-- src/main/java/com/notificationservice/shared/
|       |-- domain/
|       |-- dto/
|       `-- entity/
|-- api-service/
|   |-- pom.xml
|   |-- Dockerfile
|   `-- src/main/
|       |-- java/com/notificationservice/api/
|       `-- resources/
`-- worker-service/
    |-- pom.xml
    |-- Dockerfile
    `-- src/main/
        |-- java/com/notificationservice/worker/
        `-- resources/
```

## 3. Modules

### Parent Maven Project

File: `pom.xml`

- Group ID: `com.notificationservice`
- Artifact ID: `notification-service`
- Version: `1.0.0-SNAPSHOT`
- Packaging: `pom`
- Java version: `17`
- Spring Boot parent: `3.2.0`
- Modules:
  - `shared`
  - `api-service`
  - `worker-service`

### shared

Purpose: shared DTOs, enums, and JPA entities used by both services.

Important files:

- `shared/src/main/java/com/notificationservice/shared/domain/NotificationChannel.java`
- `shared/src/main/java/com/notificationservice/shared/domain/NotificationState.java`
- `shared/src/main/java/com/notificationservice/shared/dto/SendNotificationRequest.java`
- `shared/src/main/java/com/notificationservice/shared/dto/SendNotificationResponse.java`
- `shared/src/main/java/com/notificationservice/shared/entity/Notification.java`
- `shared/src/main/java/com/notificationservice/shared/entity/DeadLetterNotification.java`

Dependencies:

- `spring-boot-starter-data-jpa`
- `jackson-databind`
- `spring-boot-starter-validation`

### api-service

Purpose: REST API for accepting notification requests.

Important packages:

- `controller`: REST endpoints.
- `service`: idempotency, rate limiting, persistence, stream publishing.
- `repository`: JPA repository for notification lookup.
- `config`: Redis stream template, rate limit config, correlation ID filter.

Dependencies:

- `shared`
- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-data-redis`
- `postgresql`
- `flyway-core`
- `spring-boot-starter-validation`
- `spring-boot-starter-actuator`

### worker-service

Purpose: consumes Redis Stream messages and performs notification delivery through a mock provider.

Important packages:

- `worker`: Redis Stream consumer loop.
- `service`: delivery service, mock provider, transient/permanent exception types.
- `repository`: JPA repositories for notifications and dead-letter rows.
- `config`: Redis stream template and Jackson configuration.

Dependencies:

- `shared`
- `jackson-datatype-jsr310`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-data-redis`
- `postgresql`
- `flyway-core`
- `spring-boot-starter-actuator`

## 4. Architecture

```text
Client
  |
  | POST /notifications/send
  v
API Service
  - validates request
  - applies idempotency
  - checks Redis rate limit
  - writes notifications row
  - publishes Redis Stream message
  |
  | XADD notifications:stream
  v
Redis Streams
  - durable stream
  - consumer group: delivery-workers
  |
  | XREADGROUP
  v
Worker Service
  - locks notification row
  - transitions state
  - calls mock provider
  - writes SENT, RETRYING, FAILED
  - writes DLQ rows for permanent/max-retry failures
  |
  v
PostgreSQL
  - notifications
  - dead_letter_notifications
```

## 5. Runtime Infrastructure

Defined in `docker-compose.yml`.

Services:

- `postgres`
  - Image: `postgres:16-alpine`
  - Container: `notification-postgres`
  - Port: `5432:5432`
  - Database: `notifications`
  - User/password: `postgres` / `postgres`
  - Volume: `postgres_data`

- `redis`
  - Image: `redis:7-alpine`
  - Container: `notification-redis`
  - Port: `6379:6379`

- `api-service`
  - Built from `api-service/Dockerfile`
  - Container: `notification-api`
  - Port: `8080:8080`
  - Depends on healthy PostgreSQL and Redis
  - Healthcheck: `http://localhost:8080/actuator/health`

- `worker-service`
  - Built from `worker-service/Dockerfile`
  - Container: `notification-worker`
  - Depends on healthy PostgreSQL and Redis
  - No port is exposed in Compose
  - Current Dockerfile and Compose healthcheck still call `http://localhost:8080/actuator/health`; this assumes the worker actuator is reachable on port 8080 inside the container.

## 6. Main Data Flow

1. Client sends `POST /notifications/send`.
2. `NotificationController` accepts the body and optional `Idempotency-Key` header.
3. If the header is present and non-blank, it overwrites `request.idempotencyKey`.
4. `NotificationService.send()` checks for an existing row by idempotency key.
5. If a row exists, the API returns the existing `notificationId` and state with `202 Accepted`.
6. If no row exists, Redis rate limit is checked using key `rate_limit:{userId}`.
7. If the rate limit is exceeded, the API returns `429 Too Many Requests`.
8. A new `notifications` row is saved with state `PENDING` and retry count `0`.
9. The rate limit counter is incremented.
10. A Redis Stream message is added to `notifications:stream`.
11. API returns `202 Accepted` with the new notification ID and state `PENDING`.
12. Worker reads records from Redis Stream using consumer group `delivery-workers`.
13. Worker extracts `notificationId` and calls `NotificationDeliveryService.process(notificationId)`.
14. Delivery service locks the notification row with `PESSIMISTIC_WRITE`.
15. If the notification is already `SENT` or `FAILED`, processing is skipped.
16. Otherwise state moves to `PROCESSING`.
17. `MockNotificationProvider.send()` is called.
18. On success, state moves to `SENT`.
19. On permanent failure, state moves to `FAILED` and a DLQ row is inserted.
20. On transient failure, retry count increments. If max retries is reached, state moves to `FAILED` and a DLQ row is inserted; otherwise state moves to `RETRYING`.

## 7. Public API

### Send Notification

Endpoint:

```http
POST /notifications/send
Content-Type: application/json
Idempotency-Key: optional-but-preferred
```

Request body:

```json
{
  "userId": "user-123",
  "channel": "EMAIL",
  "payload": {
    "to": "user@example.com",
    "subject": "Welcome",
    "body": "Hello"
  },
  "idempotencyKey": "unique-logical-notification-id"
}
```

Response:

```http
202 Accepted
```

```json
{
  "notificationId": 1,
  "state": "PENDING"
}
```

Duplicate idempotency response:

```json
{
  "notificationId": 1,
  "state": "PENDING"
}
```

Rate limit response:

```http
429 Too Many Requests
```

```json
{
  "code": "RATE_LIMIT_EXCEEDED",
  "message": "Rate limit exceeded for user user-123"
}
```

### Request Validation

`SendNotificationRequest` requires:

- `userId`: non-blank, max 255 chars.
- `channel`: non-null enum value.
- `payload`: non-null map.
- `idempotencyKey`: non-blank, max 255 chars.

Important behavior:

- The `Idempotency-Key` header is preferred.
- If the header is present, the controller sets it onto the request body before validation reaches the service.
- If no header is supplied, the body `idempotencyKey` is used.

## 8. Domain Model

### NotificationChannel

Supported values:

- `EMAIL`
- `SMS`

### NotificationState

Supported values:

- `PENDING`
- `PROCESSING`
- `SENT`
- `RETRYING`
- `FAILED`

Intended state machine:

```text
PENDING
  -> PROCESSING
       -> SENT
       -> RETRYING
            -> PROCESSING
            -> FAILED
       -> FAILED
```

Terminal states:

- `SENT`
- `FAILED`

### Notification Entity

Table: `notifications`

Fields:

- `id`: generated primary key.
- `idempotency_key`: required, unique, max 255 chars.
- `user_id`: required, max 255 chars.
- `channel`: required, `EMAIL` or `SMS`.
- `payload`: required JSONB.
- `state`: required, default `PENDING`.
- `retry_count`: required, default `0`.
- `last_error`: optional, max 2000 chars.
- `created_at`: set at insert time.
- `updated_at`: set on JPA update.

Indexes:

- `idx_notifications_user_id`
- `idx_notifications_state`
- `idx_notifications_created_at`

### DeadLetterNotification Entity

Table: `dead_letter_notifications`

Fields:

- `id`: generated primary key.
- `notification_id`: original notification ID.
- `user_id`: original user ID.
- `channel`: original channel.
- `payload`: original payload JSONB.
- `failure_reason`: required, max 2000 chars.
- `retry_count`: retry count when dead-lettered.
- `created_at`: set at insert time.

Indexes:

- `idx_dlq_notification_id`
- `idx_dlq_created_at`

## 9. Database Migration

Migration file:

- `api-service/src/main/resources/db/migration/V1__init_schema.sql`

Creates:

- `notifications`
- `dead_letter_notifications`

Constraints:

- `uk_notifications_idempotency_key`
- `chk_notifications_channel`
- `chk_notifications_state`
- `chk_dlq_channel`

Both `api-service` and `worker-service` have Flyway enabled and point to `classpath:db/migration`. The migration file currently exists under `api-service` resources. If running the worker as an independent artifact against a fresh database, confirm that the migration is available to that artifact or that the API service has already migrated the database.

## 10. Redis Usage

### Stream

Default stream name:

```text
notifications:stream
```

API publishes stream records with fields:

- `notificationId`
- `userId`
- `channel`
- `payload` as JSON string

Worker consumer group defaults:

- Consumer group: `delivery-workers`
- Consumer name: `${HOSTNAME:worker-1}`

Worker behavior:

- Creates the consumer group on startup using offset `0-0`.
- Reads new records with `XREADGROUP`.
- Uses batch size `10`.
- Blocks up to `5s` per poll.
- Acknowledges records after `deliveryService.process()` returns.
- Leaves records pending only if `deliveryService.process()` throws.
- Periodically checks pending messages and claims messages whose idle time exceeds exponential backoff.

### Rate Limiting

Rate limit key:

```text
rate_limit:{userId}
```

Default limit:

```text
10 notifications per user per minute
```

Implementation:

- `checkRateLimit()` reads the Redis value.
- `incrementRateLimit()` increments the key after a new notification row is saved.
- The first increment sets a one-minute expiration.

Current concurrency note:

- The rate-limit check and increment are separate Redis operations, so very high concurrent traffic can overshoot the exact limit.
- Idempotency hits return before rate limiting and do not consume rate-limit quota.

## 11. Idempotency

Primary mechanism:

- `notifications.idempotency_key` has a database unique constraint.

API behavior:

- First request with a key creates a row and publishes one stream event.
- Later requests with the same key return the existing notification ID and current state.
- Concurrent duplicate inserts are handled by catching `DataIntegrityViolationException` and re-reading the existing row.

Important caveat:

- The DB write and Redis publish are not part of one distributed transaction. The publish happens inside the Spring transaction method, but Redis is not transactionally coupled to PostgreSQL. If Redis publish succeeds but the DB transaction later fails, or the DB commit succeeds but publishing fails, manual reconciliation may be needed. A transactional outbox would be the stronger production pattern.

## 12. Worker Delivery Behavior

Core class:

- `worker-service/src/main/java/com/notificationservice/worker/service/NotificationDeliveryService.java`

Processing steps:

1. Find notification by ID using pessimistic write lock.
2. If missing, log and return.
3. If already `SENT` or `FAILED`, log and return.
4. Set state to `PROCESSING`.
5. Call `MockNotificationProvider.send(channel, payload)`.
6. On success:
   - Set state `SENT`.
   - Clear `last_error`.
7. On `PermanentDeliveryException`:
   - Set state `FAILED`.
   - Set `last_error`.
   - Insert `dead_letter_notifications` row.
8. On `TransientDeliveryException` or unknown exception:
   - Increment `retry_count`.
   - Set `last_error`.
   - If retry count is at or above `notification.max-retries`, set `FAILED` and insert DLQ row.
   - Otherwise set `RETRYING`.

Current retry caveat:

- `NotificationDeliveryService.process()` handles transient failures internally and returns normally.
- `NotificationStreamConsumer.processRecord()` acknowledges the Redis message whenever `process()` returns.
- That means a transient failure currently moves the row to `RETRYING`, but the original Redis message is ACKed and no new message is scheduled.
- The pending-message claim logic only retries when `process()` throws and the message remains pending.
- For real automatic retries, update the worker so transient failures either leave the message pending, republish/schedule a retry message, or use a delayed retry mechanism.

## 13. Mock Provider

Class:

- `worker-service/src/main/java/com/notificationservice/worker/service/MockNotificationProvider.java`

Behavior:

- Logs the send attempt.
- If payload contains `"simulate": "failTransient"`, throws `TransientDeliveryException`.
- If payload contains `"simulate": "failPermanent"`, throws `PermanentDeliveryException`.
- Otherwise logs success.

Example permanent failure payload:

```json
{
  "to": "bad@example.com",
  "simulate": "failPermanent"
}
```

Example transient failure payload:

```json
{
  "to": "user@example.com",
  "simulate": "failTransient"
}
```

## 14. Configuration

### API Service

File:

- `api-service/src/main/resources/application.yml`

Key defaults:

- App name: `api-service`
- Server port: `${SERVER_PORT:8080}`
- Datasource URL: `${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/notifications}`
- Datasource username: `${SPRING_DATASOURCE_USERNAME:postgres}`
- Datasource password: `${SPRING_DATASOURCE_PASSWORD:postgres}`
- Redis host: `${SPRING_REDIS_HOST:localhost}`
- Redis port: `${SPRING_REDIS_PORT:6379}`
- Redis timeout: `3000ms`
- JPA ddl-auto: `validate`
- Flyway enabled: `true`
- Stream name: `notifications:stream`
- Max retries: `${NOTIFICATION_MAX_RETRIES:5}`
- Per-user rate limit: `${RATE_LIMIT_PER_USER_PER_MINUTE:10}`
- Actuator exposed endpoints: `health,info`

### Worker Service

File:

- `worker-service/src/main/resources/application.yml`

Key defaults:

- App name: `worker-service`
- Datasource URL: `${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/notifications}`
- Datasource username: `${SPRING_DATASOURCE_USERNAME:postgres}`
- Datasource password: `${SPRING_DATASOURCE_PASSWORD:postgres}`
- Redis host: `${SPRING_REDIS_HOST:localhost}`
- Redis port: `${SPRING_REDIS_PORT:6379}`
- Redis timeout: `3000ms`
- JPA ddl-auto: `validate`
- Flyway enabled: `true`
- Stream name: `notifications:stream`
- Consumer group: `delivery-workers`
- Consumer name: `${HOSTNAME:worker-1}`
- Max retries: `${NOTIFICATION_MAX_RETRIES:5}`
- Retry backoff initial ms: `1000`
- Actuator exposed endpoints: `health,info`

### Docker Compose Environment Variables

`api-service`:

- `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/notifications`
- `SPRING_DATASOURCE_USERNAME=postgres`
- `SPRING_DATASOURCE_PASSWORD=postgres`
- `SPRING_REDIS_HOST=redis`
- `SPRING_REDIS_PORT=6379`
- `NOTIFICATION_MAX_RETRIES=5`
- `RATE_LIMIT_PER_USER_PER_MINUTE=10`

`worker-service`:

- `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/notifications`
- `SPRING_DATASOURCE_USERNAME=postgres`
- `SPRING_DATASOURCE_PASSWORD=postgres`
- `SPRING_REDIS_HOST=redis`
- `SPRING_REDIS_PORT=6379`
- `NOTIFICATION_MAX_RETRIES=5`
- `HOSTNAME=worker-1`

## 15. Observability

### Correlation IDs

API filter:

- `CorrelationIdFilter`

Behavior:

- Reads `X-Correlation-ID` request header.
- Generates a UUID if missing.
- Stores it in MDC under `correlationId`.
- Writes it back as the `X-Correlation-ID` response header.

### Logging

API logback:

- Includes timestamp, thread, level, logger, and `correlationId`.

Worker logback:

- Includes timestamp, thread, level, logger, and message.
- Does not currently include correlation ID in its pattern.

Notable log events:

- API idempotency hits.
- API notification acceptance.
- Rate limit exceeded.
- Worker stream setup.
- Worker message processing.
- Delivery success.
- Transient retry state updates.
- Permanent failures and DLQ writes.

## 16. Running Locally

### Docker Compose

```bash
docker-compose up -d
```

API health:

```bash
curl http://localhost:8080/actuator/health
```

Send a notification:

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
      "body": "Hello"
    }
  }'
```

Test idempotency:

```bash
KEY=$(uuidgen)

curl -X POST http://localhost:8080/notifications/send \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d '{"userId":"user-123","channel":"EMAIL","payload":{"to":"user@example.com"}}'

curl -X POST http://localhost:8080/notifications/send \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d '{"userId":"user-123","channel":"EMAIL","payload":{"to":"user@example.com"}}'
```

Expected result: both responses contain the same `notificationId`.

### Maven

Build all modules:

```bash
mvn -DskipTests package
```

Run API locally after PostgreSQL and Redis are available:

```bash
mvn -pl api-service spring-boot:run
```

Run worker locally after PostgreSQL and Redis are available:

```bash
mvn -pl worker-service spring-boot:run
```

## 17. Dockerfiles

Both service Dockerfiles use a two-stage build:

1. Build stage:
   - Base: `eclipse-temurin:17-jdk`
   - Installs Maven with `apt-get`
   - Copies the full multi-module repository
   - Builds only the target service and required modules using `-pl <module> -am -DskipTests`

2. Runtime stage:
   - Base: `eclipse-temurin:17-jre-alpine`
   - Creates non-root `appuser`
   - Copies service jar to `/app/app.jar`
   - Starts with `java -jar app.jar`

API Dockerfile exposes port `8080`.

## 18. Testing Status

Current repository state:

- No `src/test` files are present.
- No unit, integration, or contract tests are currently checked in.
- `mvn -q -DskipTests package` completed successfully on 2026-07-15.

Recommended test coverage:

- API controller validation and `Idempotency-Key` precedence.
- Idempotency duplicate requests.
- Concurrent idempotency conflict handling.
- Redis rate limit behavior.
- Stream record publishing format.
- Worker success transition to `SENT`.
- Worker permanent failure transition and DLQ insert.
- Worker transient failure/retry behavior after the retry design is corrected.
- Flyway migration compatibility with JPA entities.

## 19. Current Implementation Gaps And Caveats

Keep these in mind before making changes:

- Automatic transient retries are not fully wired. Transient failures are caught inside `NotificationDeliveryService`, then `NotificationStreamConsumer` ACKs the stream record because no exception escapes.
- PostgreSQL and Redis writes are not atomic together. Consider an outbox table for production-grade consistency.
- Rate limiting uses separate read and increment operations. Consider Redis Lua scripting or atomic counter logic for strict enforcement.
- Only rate limit exceptions have a custom handler. Validation errors and other exceptions use Spring defaults.
- The worker service has Flyway enabled but its module does not contain the migration file. In Compose this may be fine if the API migrates first, but standalone worker startup against a fresh database can fail validation/migration expectations.
- The worker healthcheck probes port `8080` inside the worker container. This may work only if the worker starts an actuator web server on that port; confirm during container testing.
- The README describes structured JSON logs, but the current logback patterns are plain text.
- The stream setup catches broad exceptions and logs some setup errors as info; startup may continue even if stream/group setup is not healthy.
- `notification.retry-backoff-initial-ms` exists in worker config but the current claim logic hardcodes backoff from `1000L`.
- `parsePayload(String payloadJson)` exists in `NotificationDeliveryService` but is not used by the current stream flow, because delivery reloads the JSONB payload from the database.

## 20. Future Development Guidelines

Use this spec plus the code as the project map.

Before changing behavior:

- Check the relevant module section above.
- Confirm whether the change belongs in `shared`, `api-service`, or `worker-service`.
- Preserve idempotency behavior unless the API contract is intentionally changing.
- Treat PostgreSQL as the source of truth for notification state.
- Be explicit about Redis delivery semantics: at-least-once queue delivery plus idempotent worker state transitions.
- Add or update tests for state transitions, retry behavior, and data consistency.

When adding a real provider:

- Keep provider-specific code behind a service abstraction.
- Map provider failures into `TransientDeliveryException` or `PermanentDeliveryException`.
- Avoid blocking API threads on provider calls.
- Ensure payload validation is channel/provider aware.
- Consider provider-level idempotency keys if the provider supports them.

When improving retries:

- Decide between pending-list retry, delayed republish, sorted-set scheduling, or a dedicated retry stream.
- Ensure transient failures are not ACKed until a retry strategy has actually scheduled the next attempt.
- Keep max retry enforcement in one place.
- Make retry delays configurable.

When improving consistency:

- Consider a transactional outbox table.
- Let API write notification and outbox row in the same DB transaction.
- Let a publisher process emit stream messages from the outbox.
- Mark outbox rows published only after Redis confirms `XADD`.
