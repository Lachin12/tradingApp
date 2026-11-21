# Outbox dispatch verification

Executed on 2026-11-21 with Java 21.0.13, Maven 3.9.16, Spring Boot 3.5.16,
Testcontainers 1.21.4 and PostgreSQL 17.12. Phase 1 remains tagged separately at
`v0.1.0`; its [248-test release evidence](verification.md) is unchanged.

## Executed results

`./mvnw -B -ntp clean verify` succeeded for all six reactor projects:

| Suite | Tests | Failures/errors/skipped |
| --- | ---: | --- |
| Domain | 139 | 0 / 0 / 0 |
| Application, including outbox contracts/dispatcher/retry policy | 94 | 0 / 0 / 0 |
| PostgreSQL integration: orders | 22 | 0 / 0 / 0 |
| PostgreSQL integration: outbox | 18 | 0 / 0 / 0 |
| MVC/API contracts | 38 | 0 / 0 / 0 |
| Full HTTP/application/PostgreSQL | 3 | 0 / 0 / 0 |
| **Total** | **314** | **0 / 0 / 0** |

This adds 48 application tests and 18 PostgreSQL tests to the released baseline.
The focused infrastructure build also passed all 40 PostgreSQL tests before
the complete clean build. All three Flyway migrations executed successfully.

The outbox tests verified:

- Concurrent workers obtain disjoint claims; unpublished heads block later
  events for the same order while other orders can progress.
- Expired claims can be recovered. Token plus attempt and expiry fences reject
  stale success/failure updates, including a deliberately reused UUID token.
- ACK and retry calls blocked on a row lock recheck database time after the lock
  is acquired; they cannot complete ownership that expired during the wait.
- Failed publication schedules a future retry. Tests make eligibility/expiry
  deterministic with controlled SQL updates rather than timing sleeps.
- Publication runs after claim commit and outside a transaction. Another worker
  can progress while a test publisher is blocked. Ambient transactions are
  rejected before claims or publication.
- Claim/ACK rollback leaves recoverable state. A simulated publish-before-ACK
  crash permits the same immutable event and stable ID to be observed twice.
- A populated V2 database upgrades to V3 while retaining published timestamps,
  attempts and history; missing delivery metadata is conservatively backfilled.
- Interruption restores the flag, database failures propagate, retry growth is
  bounded, and duration/run limits are enforced.

An initial unit run failed because a strict Mockito publication fixture covered
only the second send, making the first mocked send throw an argument-mismatch
exception. Both publication fixtures were corrected and the focused test and
complete build were rerun successfully. No test was disabled or skipped.

`git diff --check` and relative documentation links passed. Hosted CI is a
separate check on the PR; local results do not stand in for it.

## Scope and limits

Publishers in these tests are controlled doubles. Crash windows are simulated
through committed claims, omitted acknowledgements and forced expiry; tests do
not kill operating-system processes or exercise an external transport. There is
no installed publisher, scheduler or dispatch HTTP endpoint. No Kafka, Redis,
consumer deduplication, real network timeout, production failover, benchmark or
strict external arrival ordering was verified.

The existing full HTTP tests ran against the V3 schema. Docker Compose was
verified for Phase 1; it was not rerun for this component-only increment.
See [the dispatch contract](outbox-delivery.md) for the actual guarantees and
remaining adapter responsibilities.
