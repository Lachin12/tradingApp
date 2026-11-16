# Trading Order Management System

A Java 21 portfolio project exploring order lifecycle correctness in a simulated
trading system. This is an OMS, not a matching engine, and it has no connectivity
to real brokers or exchanges.

**Current milestone: project scaffold and tested domain model.** This repository
does not yet implement the complete Phase 1 API or persistence layer. Acceptance
is distinct from execution: a MARKET order stays `ACCEPTED` until an explicit
execution report is applied.

## Build locally

Install a **full JDK 21** (not just a JRE), set `JAVA_HOME` if necessary, and run:

```sh
./mvnw --batch-mode --no-transfer-progress clean verify
```

Windows PowerShell:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

The checksum-verified Maven Wrapper downloads Maven 3.9.16 on its first run.
Internet access to Maven Central is required initially. A proxy, if needed, is
configured in your user Maven `settings.xml`; no machine-specific settings or
credentials are committed. Docker and PostgreSQL are not needed for this
domain milestone.

Run only domain tests:

```sh
./mvnw --batch-mode --no-transfer-progress -pl oms-domain -am test
```

The Spring Boot composition root is packaged as an executable JAR. It currently
starts a non-web context and exits because no server or background worker exists:

```sh
java -jar oms-bootstrap/target/oms-bootstrap-0.1.0-SNAPSHOT.jar
```

There are no REST endpoints yet; HTTP examples and OpenAPI documentation will
arrive with the API implementation. GitHub Actions runs the full reactor build
and tests on pull requests and pushes to `main`.

## Architecture

```text
oms-domain          Pure Java aggregates, value rules and immutable events
oms-application     Reserved for use cases and ports; depends on domain
oms-infrastructure  Reserved for adapters; depends on application
oms-api             Reserved for REST DTOs/controllers; depends on application
oms-bootstrap       Spring Boot composition root; depends on API/infrastructure
```

Only `oms-domain` currently implements business behaviour. The other boundary
modules are deliberately small scaffolds. The domain has no runtime library
dependencies; Maven Enforcer rejects compile/runtime JAR dependencies there.
Controllers, persistence rows and domain models will be separate types.

See [the architecture decision](docs/adr/0001-modular-monolith.md),
[the initial repository review](docs/repository-review.md) and
[the reference project review](docs/references.md).

## Lifecycle and exact values

| Current state | Legal next states |
| --- | --- |
| NEW | ACCEPTED, REJECTED, CANCELLED |
| ACCEPTED | PARTIALLY_FILLED, FILLED, CANCELLED |
| PARTIALLY_FILLED | PARTIALLY_FILLED, FILLED, CANCELLED |
| FILLED, CANCELLED, REJECTED | None |

Only `fill(Execution, recordedAt)` changes executed quantity. An order cannot be
filled before acceptance, overfilled or filled at a price worse than its limit.
Executions require stable IDs; duplicate IDs are rejected even if their payload
changes. Application-level submission idempotency is a separate, pending concern.

Prices and quantities use `BigDecimal`, with a `NUMERIC(28,8)` compatibility
policy and `RoundingMode.UNNECESSARY`. Inputs must be positive and conform to the
instrument's tick size and quantity step. Nonzero excess fractional digits and
overflow are rejected; values are never silently rounded. Currency is recorded
on the instrument. Settlement amounts, fees and notional calculations are not
implemented and need their own precision policy.

Every successful command returns a new immutable order snapshot and appends one
immutable event. Event identities are `orderId:sequence`. Order and execution
IDs, plus command timestamps, are provided by callers, making tests repeatable.
Equal timestamps are ordered by event sequence; backwards lifecycle timestamps
are rejected. Domain revision is **not** database optimistic locking.

Example in Java (no Spring context required):

```java
var at = Instant.parse("2026-01-01T09:00:00Z");
var account = new Account(
        UUID.fromString("00000000-0000-0000-0000-000000000001"), "Demo", true);
var instrument = new Instrument(
        UUID.fromString("00000000-0000-0000-0000-000000000002"), "DEMO",
        Currency.getInstance("USD"), new BigDecimal("0.01"), new BigDecimal("1"));
var order = Order.submit(
        UUID.fromString("00000000-0000-0000-0000-000000000003"),
        account, instrument, Side.BUY, OrderType.MARKET,
        new BigDecimal("10"), null, at);
var accepted = order.accept(at); // ACCEPTED, filled quantity remains zero
```

The example uses `io.github.lachin12.oms.domain` types and standard Java imports.
See [lifecycle and decimal decisions](docs/adr/0002-order-lifecycle-and-decimals.md)
for detailed semantics and tradeoffs.

## Tests and limitations

JUnit 5 tests cover the complete state transition matrix, aggregate commands in
each state, exact decimal bounds, extreme exponents, tick/step rules, BUY/SELL
limits, partial and full fills, duplicate execution IDs, invalid event shapes,
immutable history, timestamp ordering and deterministic identities.

Cancellation/fill tests exercise sequential domain outcomes. They do **not**
prove database concurrency safety. Cancellation is final in this local
simulation; late execution reports are rejected. A real venue would require
cancel-request/acknowledgement and reconciliation semantics. Remaining quantity
means `quantity - filledQuantity`, including unexecuted quantity on a cancelled
order; it does not imply the cancelled order is still working.

History is immutable **in memory**, not durably append-only yet. The aggregate
retains/copies full history, appropriate for this small starting model rather
than unbounded high-volume execution histories. No latency or throughput claim
is made.

## Remaining Phase 1 work

1. Application use cases and submission idempotency contract, with canonical
   decimal fingerprints and account-scoped keys.
2. PostgreSQL/Flyway persistence with unique constraints, version-checked writes,
   immutable event records and atomic order/event/idempotency transactions.
3. Testcontainers integration tests for duplicate submissions, rollback,
   concurrent updates and controlled cancellation/fill races.
4. REST submission/retrieval/listing/cancellation, error contracts and OpenAPI.
5. Docker Compose and local data/setup instructions.

Kafka, Redis, a risk engine and an execution simulator are future adapters, not
dependencies of the current domain. Durable events will provide an outbox seam
for later reliable publishing; delivery retries and consumer deduplication will
be explicit. This project makes no cross-system exactly-once claim. See the
[incremental roadmap](docs/phase1-roadmap.md).
