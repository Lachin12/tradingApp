# Execution simulation verification

Executed on 2026-11-21 with Java 21.0.13, Maven 3.9.16, Spring Boot 3.5.16,
Testcontainers 1.21.4 and PostgreSQL 17.12. This increment follows the merged
[outbox increment](outbox-verification.md).

## Build and tests actually run

`./mvnw -B -ntp clean verify` succeeded for all six reactor projects:

| Suite | Executed tests | Failures/errors/skipped |
| --- | ---: | --- |
| Domain | 139 | 0 / 0 / 0 |
| Application | 140 | 0 / 0 / 0 |
| PostgreSQL integration | 66 | 0 / 0 / 0 |
| MVC/API contracts | 70 | 0 / 0 / 0 |
| Full HTTP/application/PostgreSQL | 6 | 0 / 0 / 0 |
| **Total** | **421** | **0 / 0 / 0** |

Before the complete build, the application test build passed and the focused
infrastructure verification passed all 66 PostgreSQL cases. No tests were
disabled or skipped.

The added coverage includes deterministic step IDs, explicit partial/full fills,
current-state replay with stale versions, changed-report conflicts, concurrent
identical reports, receipt-owner rollback and takeover, atomic receipt/event/
outbox rollback, immutable receipts, four cancellation/fill commit orderings,
and populated V3-to-V4 receipt backfill. Existing order and outbox tests passed
with all four migrations applied.

Real Spring/PostgreSQL HTTP tests prove the route is absent by default and
missing from OpenAPI, then exercise the enabled route, decimal responses,
partial/full fills, replay after completion, invalid/future timestamps and
absence of committed receipts after failures. Unsupported Java timestamp ranges
are rejected before any receipt SQL rather than becoming server failures.

## Packaged runtime and demo

The Docker image built successfully, but this workspace's Docker storage driver
ran out of disk space while allocating the application container. The complete
two-container Compose application flow therefore did **not** complete for this
increment. Scratch archives and obsolete local build data were removed; source
and test reports were preserved.

Runtime verification continued with the packaged executable JAR on the host and
the Compose PostgreSQL service. It used the local sample credentials and a
separate verification project/database. The following checks passed:

- With simulation disabled, application health was `UP`, the step route returned
  404 and OpenAPI contained no simulation route.
- With simulation enabled, health was `UP` and the route appeared in OpenAPI.
- `./scripts/demo-simulation.sh` created an accepted MARKET order, applied `4`
  then `6` units at explicit price `100.25`, and observed partial then full fill.
- Running the script again replayed the same execution IDs and current `FILLED`
  state without appending another execution.
- After terminating and restarting the actual Java application process, another
  script run still replayed both completed steps and the same order ID.
- Direct PostgreSQL inspection confirmed the demo order was `FILLED`, version 2,
  filled quantity `10.00000000`, with two receipts, four lifecycle events and
  four outbox records. The script independently checked the same history length
  after the process restart.

An early enabled launch encountered port 8080 still held by the preceding local
instance. That process was stopped before the successful enabled runs. This was
a runtime-harness issue; the passing checks above were rerun after it was fixed.

`bash -n scripts/demo-simulation.sh`, Compose configuration validation,
`git diff --check` and relative documentation link checks passed. Hosted CI is
separate evidence on the PR and is not implied by local results.

## Limits

This verifies deterministic local execution reports, not a matching engine or
real venue execution. There is no risk engine, broker connection, scheduler,
authentication, external outbox publisher or throughput/latency benchmark.
Prices and source timestamps are caller inputs. Crash/power-loss failover and
production database operations were not tested. The successful process restart
retained the same PostgreSQL database; it was not a database recovery exercise.

See [the simulation guide](execution-simulation.md) and
[ADR 0005](adr/0005-execution-simulation.md) for the contract and boundaries.
