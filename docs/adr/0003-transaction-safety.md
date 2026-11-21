# ADR 0003: Atomic submissions, optimistic updates and durable lifecycle events

Status: Accepted for the Phase 1 application and persistence increment.

## Context

Immutable domain objects reject invalid commands, but two valid commands made
from the same old snapshot need database arbitration. Submission retries must
not create second orders, and committed changes must retain their lifecycle
facts. These guarantees require PostgreSQL transactions and constraints.

## Decisions

The framework-free application service defines the unit of work through a
transaction port. JDBC and Spring provide its implementation. Controllers map
DTOs and errors; they do not own trading rules or transaction orchestration.

### Submission idempotency

A key is 1–128 visible ASCII characters and scoped to an account. The database
enforces uniqueness of `(account_id, idempotency_key)`. Claim, accepted order,
initial lifecycle events and outbox records share one transaction. A competing
claim waits for the owner and reads its committed result. A rolled-back owner
leaves no successful claim. No process-local lock is needed.

The SHA-256 fingerprint uses a versioned, unambiguous canonical form containing
account, instrument, side, type, quantity and optional limit price. Decimal
validation/normalization precedes hashing, making equivalent decimal spellings
equal. Different terms under an existing key produce a conflict.

An identical replay returns the current order with HTTP 200; creation returns
201. This is resource idempotency, not an original-response cache. A retry after
cancellation sees `CANCELLED`. Validation and transaction failures do not reserve
keys. Keys do not expire in Phase 1; retention/archival needs a policy before
pruning them.

### Optimistic updates and cancellation races

Stored version starts at zero for the submitted and accepted order. Each later
persisted command updates by order ID **and expected version**, incrementing the
version once. No matching row means a concurrency conflict. An early stale-read
check improves errors, but SQL comparison closes the read/write race.

State, new events and outbox rows commit together. A stale update, duplicate
execution or failed event insert rolls back the whole command. Conflicts are
not automatically retried: callers reload and revalidate intent. Cancellation
cannot overwrite a committed fill. A partial fill followed by cancellation on
a fresh version can both succeed while preserving the fill.

Cancellation is final locally. A full fill before cancellation prevents cancel;
cancellation before a fill prevents that fill. Real venue connectivity needs a
different cancellation and late-fill reconciliation protocol.

### Immutable history and reference data

PostgreSQL stores unique per-order sequences and execution identities. Lifecycle
facts are append-only, with database enforcement against ordinary updates and
deletes. The adapter replays history through domain commands and checks that
stored state agrees. This is a snapshot plus validated-history model, not a
distributed event-sourcing implementation.

Each order stores the account/instrument snapshot used at submission. Reading
an old order does not substitute changed reference data for its original active
flag, symbol, currency, tick size or quantity step. Flyway V2 seeds demonstration
reference data; no reference-data management API is provided.

Superusers and schema owners can change database enforcement. The local Compose
role also runs migrations and is not a hardened production role arrangement.
Separate migration/runtime roles and audit controls remain future work;
append-only triggers are not tamper-proof storage.

### Time and identities

The application receives clocks and ID suppliers. Recording timestamps are
truncated to PostgreSQL microsecond precision before constructing facts. A
backwards clock is clamped to the last event time; sequence orders ties.
Execution source times are also truncated to microseconds and may precede
recording time but cannot follow it. No clock or randomness is hidden in the
domain.

Events use `orderId:sequence`. Domain revision counts appended facts; the
independent database version counts persisted commands. Submission has two
facts (`SUBMITTED`, `ACCEPTED`) but stored version zero. These numbers are not
interchangeable.

### Outbox boundary

Each lifecycle fact creates a durable outbox record in the same transaction.
Stable identity and per-order sequence allow a future publisher to retry and
consumers to deduplicate. Delivery bookkeeping is separate from immutable
business history.

There is no worker, Kafka producer, Redis cache or external delivery guarantee
in Phase 1. Pending rows provide the seam for later publication; they do not
deliver anything. Publication must define claiming, retry/backoff, ordering,
retention and consumer idempotency. Cross-system exactly-once effects are not
claimed.

## Evidence and consequences

PostgreSQL Testcontainers tests target concurrent identical/conflicting
submissions, stale versions, cancellation/fill races, rollback, preserved
history and append-only enforcement. Only results in
[verification](../verification.md) establish what was actually run.

One transaction provides a small consistency boundary. Replay validation and
history copies cost work proportional to order history; volume handling,
archival and load measurements remain future work. No matching engine,
broker or cache dependency is introduced.
