# Phase 1 increments and follow-up work

The implementation progresses from the domain foundation to application
contracts, PostgreSQL consistency, REST and local operation. Each increment
needs relevant tests; [verification](verification.md) records observed runs.

| Increment | Implementation scope |
| --- | --- |
| 1 — domain | Maven boundaries; pure Java Account, Instrument, Order, Execution and immutable OrderEvent; lifecycle/decimal tests; wrapper and CI. |
| 2 — application | Submission, lookup, bounded listing, cancellation and internal execution; injected IDs/clocks; storage/transaction ports; canonical fingerprints and scoped idempotency. |
| 3 — persistence | Flyway and demo reference data; atomic claims; version-checked updates; immutable history and reference snapshots; transactional outbox. |
| 4 — integration evidence | Real PostgreSQL tests for duplicate/conflicting requests, rollback, stale writes, cancellation/fill races, history integrity and constraints. |
| 5 — API/operations | DTOs, stable errors, OpenAPI, application/PostgreSQL Compose services, non-root runtime image, health endpoint and examples. |

Phase 1 accepts valid orders locally. MARKET requests never imply fills. There
is no public execution-simulation route. Cancellation requires expected version
and conflicts on stale writes; callers reload and revalidate before retrying.

## Follow-up work

1. **Reliable delivery worker.** Define outbox claiming, retry/backoff, per-order
   ordering, recovery, retention and idempotent consumption. Pending durable
   records exist; external event delivery does not. Add Kafka only alongside
   explicit failure semantics and tests.
2. **Risk/execution adapters.** Add concrete risk decisions before acceptance
   and deterministic simulation through the internal execution boundary.
   Preserve duplicate-execution handling. Real venue/FIX connectivity would
   require cancel acknowledgements, late-fill reconciliation and session
   recovery, beyond this simulation.
3. **Access/operational hardening.** Authentication, account authorisation,
   least-privilege migration/runtime roles, secrets, audit, backup/restore and
   migration rollout. Local seed data and credentials are demonstrations.
4. **Measured scale/observability.** Keep metric tags bounded; instrument command
   outcomes and outbox backlog; measure representative workloads. Reduce full
   history replay/copy costs when justified. Extend bounded offset listing to
   stable cursor pagination if needed.
5. **Financial scope.** Define fee, notional, average-price, balance and settlement
   precision before calculating them. Keep monetary calculations exact.

Redis requires a demonstrated cache or coordination need; it is not part of
current consistency. No real trading, broker connectivity, exchange-latency
benchmark or cross-system exactly-once claim is implied.
