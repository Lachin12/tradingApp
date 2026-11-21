# Trading Order Management System

A Java 21, Spring Boot and PostgreSQL portfolio project focused on order
lifecycle correctness, atomic idempotency, immutable execution history and
concurrent updates. This simulated OMS has no real broker or exchange
connectivity. Acceptance is separate from execution: a MARKET order stays
`ACCEPTED` until an explicit execution report supplies a fill.

Phase 1 implements submission, retrieval, bounded listing, cancellation and
lifecycle history through REST; PostgreSQL persistence with Flyway; a durable
outbox; and JUnit 5, Mockito and Testcontainers tests. See
[verification](docs/verification.md) for checks actually executed and environment
limitations. No production-readiness, trading-latency or external-delivery claim
is made.

## Run locally

Prerequisites: a **full JDK 21**, Docker Engine/Desktop with Compose v2, and
network access to Maven Central and the container registry. The checksum-pinned
Maven Wrapper downloads Maven 3.9.16 on its first run. Full verification requires
a working Docker daemon because integration tests use PostgreSQL containers.

```sh
cp .env.example .env
./mvnw --batch-mode --no-transfer-progress clean verify
docker compose up --build
```

The image copies the already packaged executable JAR; run Maven again before
rebuilding after source changes. It uses Temurin JRE 21 and a non-root user.
Compose pins PostgreSQL `17.12-bookworm`, waits for database health, persists data
in `oms-data`, and binds ports 8080 and 5432 to localhost. Flyway runs versioned
migrations at startup. `.env.example` credentials are for local simulation only;
`.env` is ignored by Git.

Check readiness and documentation:

```sh
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:8080/v3/api-docs
```

Open [Swagger UI](http://localhost:8080/swagger-ui.html). Wait for application
health `UP`; database health alone does not establish HTTP readiness. Stop with
`docker compose down`, omitting `--volumes` to preserve data. Changing credentials
after initial startup does not change the user in an existing database volume.

To run the application outside Docker:

```sh
docker compose up -d postgres
export OMS_DB_USER=oms
export OMS_DB_PASSWORD=oms-local-development-only
java -jar oms-bootstrap/target/oms-bootstrap-0.1.0-SNAPSHOT.jar
```

The default database URL is `jdbc:postgresql://localhost:5432/oms`. Set credentials
to match your `.env`. `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
`SPRING_DATASOURCE_PASSWORD` override defaults. On Windows use `.\mvnw.cmd` and
equivalent PowerShell environment-variable syntax.

## API examples

Flyway V2 seeds an active demo account and `DEMO` instrument (USD, price tick
`0.01`, quantity step `1`). Phase 1 has no reference-data management endpoints.
Use JSON strings for decimal values to preserve exact values in clients:

```sh
curl -i http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: portfolio-demo-001' \
  -d '{
    "accountId": "00000000-0000-0000-0000-000000000001",
    "instrumentId": "00000000-0000-0000-0000-000000000002",
    "side": "BUY",
    "type": "LIMIT",
    "quantity": "10",
    "limitPrice": "100.25"
  }'
```

The first success returns `201`, `ACCEPTED` and database `version: 0`. Repeat the
same key and equivalent payload to get `200` with the **current** order snapshot.
If it was cancelled between retries, the retry returns `CANCELLED`; this contract
does not cache original response bytes. Reusing the key for different terms
returns `409`. Keys contain 1–128 visible ASCII characters and are account-scoped.
Decimal forms such as `10` and `10.00000000` share a fingerprint. A MARKET request
omits `limitPrice` and does not create an execution.

Copy the response `id` and latest `version`:

```sh
ORDER_ID='replace-with-response-id'
curl "http://localhost:8080/api/v1/orders/$ORDER_ID"
curl 'http://localhost:8080/api/v1/orders?accountId=00000000-0000-0000-0000-000000000001&limit=20&offset=0'
curl "http://localhost:8080/api/v1/orders/$ORDER_ID/events"
curl -i "http://localhost:8080/api/v1/orders/$ORDER_ID/cancel" \
  -H 'Content-Type: application/json' \
  -d '{"expectedVersion":0,"reason":"Portfolio cancellation example"}'
```

Cancellation requires the latest version. A stale version returns `409`; reload
and revalidate intent before retrying. Illegal lifecycle operations also return
conflicts. Missing resources return `404`, invalid input `400`. Listing requires
an account ID and bounds `limit` to 1–100 and `offset` to 0–10000. There is no
public fill route: a future execution adapter can call the internal use case.

## Architecture and transaction decisions

```text
oms-domain          Pure Java aggregate, exact values, immutable lifecycle facts
oms-application     Use cases, ID/clock injection, transaction and storage ports
oms-infrastructure  JDBC adapters, PostgreSQL constraints, Flyway, transactions
oms-api             REST DTOs, validation, error mapping and OpenAPI
oms-bootstrap       Spring Boot composition root and runtime configuration
```

Domain/application logic is independent of controllers and JDBC rows. The domain
has no runtime library dependencies; Maven Enforcer rejects compile/runtime JAR
dependencies there. Orders store original reference-data snapshots so later
account/instrument changes cannot rewrite historical trading terms.

Submission claims its scoped key through a database unique constraint, then saves
the accepted order, lifecycle events and outbox records in one transaction. A
SHA-256 fingerprint includes normalized decimal terms. Failed validation or
persistence rolls back the claim. Concurrent identical requests create one order.

Updates compare the expected stored version in SQL and increment it once per
successful update. State, events and outbox commit together. Cancellation and
execution against one version cannot both commit. The loser receives a conflict
and is not automatically retried. Previously committed fills stay in the history.

Lifecycle history is append-only. The outbox stores durable pending records with
stable event IDs and per-order sequences; **no publisher or delivery worker is
implemented**. Persisting rows does not establish external delivery or
cross-system exactly-once effects.

See [modular monolith](docs/adr/0001-modular-monolith.md),
[lifecycle and decimals](docs/adr/0002-order-lifecycle-and-decimals.md) and
[transaction safety](docs/adr/0003-transaction-safety.md). Earlier ADRs describe
the first increment; ADR 0003 adds persistence and API decisions.
[Repository review](docs/repository-review.md) records the empty baseline and
ranked priorities. [References](docs/references.md) explains principles extracted
independently from the requested projects.

## Lifecycle and exact values

| Current state | Legal next states |
| --- | --- |
| NEW | ACCEPTED, REJECTED, CANCELLED |
| ACCEPTED | PARTIALLY_FILLED, FILLED, CANCELLED |
| PARTIALLY_FILLED | PARTIALLY_FILLED, FILLED, CANCELLED |
| FILLED, CANCELLED, REJECTED | None |

Only an execution changes filled quantity. Orders cannot execute before
acceptance, overfill, reuse execution IDs, or execute worse than a LIMIT price.
Invalid terms fail before creating an order. `REJECTED` represents a decision on
an existing `NEW` order for a future risk module; valid submissions are accepted
directly by the current use case.

Prices and quantities use `BigDecimal`, matching PostgreSQL `NUMERIC(28,8)`:
20 integer digits and eight fractional digits. `RoundingMode.UNNECESSARY` rejects
nonzero excess fractional digits and overflow. Positive values must align to
instrument ticks/quantity steps. No floating-point monetary calculations occur.
Fees, notional and settlement need separate precision policies.

Domain commands return immutable snapshots and append immutable events identified
by `orderId:sequence`. IDs and clocks are injected. Application recording times
use PostgreSQL microsecond precision and clamp backwards clock movement to the
last event time; sequence orders ties. Execution source timestamps are truncated
to microseconds and cannot follow recording time. Domain revision counts events;
database version counts persisted updates, so these values are distinct.

## Tests and limits

```sh
# Pure domain checks; no Docker required
./mvnw --batch-mode --no-transfer-progress -pl oms-domain -am test
# Complete reactor, including PostgreSQL integration tests
./mvnw --batch-mode --no-transfer-progress clean verify
```

Unit tests target lifecycle transitions, decimal bounds, tick/step rules, fills,
immutability, fingerprints and use-case orchestration. PostgreSQL integration
tests target migrations, duplicate requests, rollback, version conflicts and
controlled cancellation/fill races. HTTP tests cover the public contract. See
[verification](docs/verification.md) for observed runs; existence is not evidence
of a passing test.

Cancellation is final in this simulation; later fills fail. Real connectivity
requires cancel-request/acknowledgement, late-fill reconciliation and recovery.
`remainingQuantity` means requested minus filled, including unexecuted quantity
on a cancelled order.

Aggregate rehydration replays history for validation, and appends copy history.
This small implementation needs further work for unbounded execution journals.
Bounded offset pagination is not a consistent snapshot across HTTP requests.

There is no authentication, account authorisation, broker/FIX connectivity,
Kafka, Redis, risk engine, execution simulator, settlement or performance
benchmark. The local database is not a production credential/role model. Keep
the unauthenticated demo API local. See [follow-up work](docs/phase1-roadmap.md).
