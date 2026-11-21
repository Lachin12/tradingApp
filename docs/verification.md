# Phase 1 verification

Verified on **2026-11-21** with Temurin JDK 21.0.13, Maven Wrapper 3.3.4,
Maven 3.9.16, Spring Boot 3.5.16, Testcontainers 1.21.4, PostgreSQL 17.12,
Docker Engine 29.9.2 and Docker Compose 2.40.3. The application runtime image
uses Temurin JRE 21.0.13.1. This records executed checks, not intended checks.

## Maven build and tests

`./mvnw -B -ntp clean verify` completed successfully for all six reactor
projects and packaged the executable application JAR. Surefire and Failsafe XML
reports contained **248 tests, zero failures, zero errors and zero skipped**
after the final release review:

| Suite | Executed tests |
| --- | ---: |
| Domain unit tests | 139 |
| Application unit tests | 46 |
| API unit/HTTP contract tests | 38 |
| PostgreSQL integration tests (`JdbcOmsStoreIT`) | 22 |
| Full HTTP/application/PostgreSQL integration tests (`OmsHttpIT`) | 3 |
| **Total** | **248** |

The domain suite includes 15 aggregate-rehydration cases in addition to the
original lifecycle/decimal coverage. Application tests cover canonical request
fingerprints, idempotent replay/conflicts, ports, deterministic clocks and IDs.
API tests exercise validation and error mapping separately from persistence.

The PostgreSQL tests ran against `postgres:17.12-bookworm` through
Testcontainers. They exercised atomic duplicate submissions, claim takeover
after rollback, conflicting keys, invalid-term rollback, deferred foreign-key
integrity, event/outbox insert failures, version conflicts, cancellation/fill
interleavings, execution uniqueness, immutable history, timestamp precision,
reference snapshots and coherent reads during updates.

The concurrency evidence includes
`blockedSqlCompareAndSwapRechecksVersionAfterTheWinnerCommits`: the executed
report confirms a writer blocked inside PostgreSQL subsequently loses the SQL
version comparison after the winning transaction commits. Other race tests
exercise both cancellation-first and fill-first outcomes, preserving earlier
fills and requiring fresh-version reload/revalidation after conflicts.

The three full HTTP tests started the actual Spring application against
PostgreSQL and exercised submission, retries, retrieval, cancellation,
documentation, invalid inputs and the effect of an internal execution on later
HTTP cancellation. No public execution route was introduced.

Early attempts exposed two new test-fixture errors and a compile-time ambiguous
integration-test import. Those were repaired before the successful complete
run. Initial failures are not counted as passing evidence, and no tests were
skipped to obtain the results above.

Final review found that broad argument/state exception handlers could classify
internal persistence failures as client errors and expose their messages. The
application now translates only known command-validation and lifecycle failures
into explicit public error types. Unexpected adapter, transaction and clock
failures propagate to sanitized HTTP 500 responses. Regression tests cover both
typed business errors and internal argument/state failures during retrieval and
listing. The complete clean build above was rerun after this fix.

## Packaged application and Docker Compose

The packaged JAR was built into the non-root Temurin runtime image. Compose was
started with a scratch copy of `.env.example` and the isolated project name
`oms-portfolio-verification`. PostgreSQL reported healthy, Flyway applied the
migrations, and the application became ready. The following checks passed
against the running application:

| Check | Observed result |
| --- | --- |
| `GET /actuator/health` | HTTP 200, `UP`. |
| `GET /v3/api-docs` | HTTP 200; order routes present. |
| `GET /swagger-ui.html` | Swagger UI loaded successfully with final HTTP 200. |
| Order submission | HTTP 201, `ACCEPTED`, filled quantity zero. |
| Equivalent decimal submission retry | HTTP 200, same order ID. |
| Same key with changed terms | HTTP 409. |
| Quantity off the instrument step | HTTP 400. |
| Order listing/retrieval, cancellation and event history | Successful; history contained `SUBMITTED`, `ACCEPTED`, `CANCELLED`. |
| Cancellation using stale version zero | HTTP 409 after the first cancellation advanced version. |
| Original submission replay after cancellation | HTTP 200 with current `CANCELLED` snapshot. |
| Direct PostgreSQL inspection after the smoke flow | One order, one submission key, three lifecycle events and three outbox records. |

Compose configuration validation and `git diff --check` also passed. Runtime
credentials used the local simulation sample and were not committed. These
checks verify this local flow; they do not establish unattended deployment,
backup/recovery or production operations.

## Build environment and earlier increment

The workspace initially supplied a Java runtime without a working development
compiler. A full JDK was downloaded to scratch tooling and checksum-verified
against official Adoptium metadata. Builds used that JDK, a scratch Maven cache,
proxy settings and the environment Java CA trust store. Machine-specific paths,
settings and credentials are not part of the project. The Maven distribution
was verified against its official checksum; the committed wrapper pins SHA-256.

The earlier scaffold/domain increment was verified on 2026-11-16 with 124
passing domain tests and successful non-web bootstrap startup. That historical
result predates the REST and database implementation; the current complete
verification is recorded above.

GitHub Actions configuration is included, but local verification does not prove
that the updated hosted workflow passed. Inspect the pull request checks for
that separate result. Windows wrapper execution was not tested.

No outbox publisher, broker delivery, Redis, risk engine, execution simulator,
real broker/exchange connectivity, performance benchmark or latency target was
verified. Durable pending outbox rows are not evidence of external delivery.
