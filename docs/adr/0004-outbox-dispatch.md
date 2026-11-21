# ADR 0004: Leased outbox dispatch with fenced completion

Status: Accepted for the outbox-dispatch increment. Executed verification
is recorded in [outbox verification](../outbox-verification.md).

## Context

[ADR 0003](0003-transaction-safety.md) commits immutable lifecycle events,
outbox envelopes and mutable delivery metadata in the order transaction. A
later delivery component must recover from crashes without holding database
locks across external publication. Multiple workers must coordinate through
PostgreSQL, and stale workers must not complete another worker's lease.

There is no Kafka, Redis or real transport in this increment. The deployment
must remain inert unless an explicit publisher and caller are supplied.

## Decision

### Application ports and message identity

Use framework-free types in `application.outbox`:

- `OutboxMessage(OrderEvent event, int schemaVersion)`: schema version is 1;
  `eventId()` is the immutable `orderId:sequence` identity.
- `DeliveryClaim(OutboxMessage message, UUID token, int attempt, Instant
  leaseUntil)`: attempt starts at 1; the database supplies the expiry.
- `OutboxStore`: claim eligible records, acknowledge a current claim, or
  reschedule a current claim after publication failure.
- `EventPublisher.publish(OutboxMessage) throws Exception`: a future transport
  adapter with an explicit bounded-timeout contract.
- `TransactionContext.active()`: detects an ambient transaction so dispatch can
  enforce its publication boundary without a Spring dependency in application
  code.

Schema 1 is a lifecycle notification: status, cumulative filled quantity and
type-specific execution/reason fields. It does not include account, instrument
or original order terms, and is not a self-contained order projection or an
execution instruction. A richer payload requires an explicit future schema
version decision. A transport serialization format is not installed here.

Retries retain the same message and event ID. Delivery attempts do not append
or change business lifecycle facts and do not change order state/version.
Publisher acceptance does not mean an order was executed.

### PostgreSQL claims and authoritative time

The JDBC adapter leases eligible unpublished heads using a short writable
transaction and `FOR UPDATE SKIP LOCKED`. An event is eligible only when every
earlier event for its order has been acknowledged. A live lease, future retry
or failed head blocks later events for that order; it does not block eligible
heads for other orders. No global ordering or fairness guarantee is added.

Claiming increments attempts and stores a new UUID token and lease expiry.
PostgreSQL time determines retry eligibility and lease expiry. Worker clocks
are not authoritative. A lease must contain whole milliseconds between one
second and five minutes, inclusive.

Acknowledgement and failure rescheduling lock the delivery row first, then
sample the database clock. Both require matching event ID, token and attempt,
an unpublished record, and an unexpired lease. Sampling time before a lock
wait would permit an expired worker to finish using an old timestamp. A failed
fence returns a lost-lease result; it does not modify delivery state.

### Transaction and publication boundaries

Each dispatcher iteration:

1. Claims one fresh event and commits that claim transaction.
2. Calls the publisher outside the database transaction.
3. Acknowledges success, or schedules retry after failure, in a separate
   transaction with the ownership/expiry fence.

A run processes at most the supplied limit, from 1 to 100. Claiming one event
immediately before its publication avoids allowing a prefetched batch's later
leases to age while earlier calls block. Dispatch rejects entry with an active
ambient transaction before claiming, and checks the transaction context again
before publication. The Spring adapter implements that context from actual
transaction activity. This enforced precondition prevents an outer transaction
from keeping the claim uncommitted while the publisher runs.

Database claim/completion failures propagate to the caller. They are not
misclassified as transport failures. A publisher exception reschedules the
current claim with bounded exponential backoff. The first failed attempt uses
the initial delay; later attempts double it to the maximum, capped at one hour.
Initial and maximum delays are whole milliseconds in the range 1 ms to one
hour, with initial no greater than maximum. There is no jitter, retry-count
cutoff or dead-letter policy in this increment.

If publication throws `InterruptedException`, the dispatcher reasserts the
thread interrupt and stops by propagating a dedicated interruption exception.
It leaves the lease for recovery rather than trying to start another database
operation on the interrupted path.

### Publisher timeout and recovery contract

Every concrete publisher must enforce a bounded timeout shorter than the
lease, allowing time for completion bookkeeping. This includes connection and
publication/confirmation waits. The synchronous dispatcher does not add an
executor or promise that it can terminate an arbitrary blocking implementation.
A transport adapter and its timeout behaviour need their own tests before use.

A crash after publishing but before committing acknowledgement permits another
worker to publish the same event after expiry. Delivery therefore permits
at-least-once attempts and duplicates; consumers must deduplicate using the
stable event ID and their own atomic business-effect boundary. With no real
publisher installed, the application makes no external delivery guarantee.

### Ordering limit of lease fencing

The unpublished-head rule preserves claim eligibility per order. It does not
prove physical arrival order at an external sink. An expired worker's external
publish may complete after a recovering worker publishes and acknowledges the
same head and advances to the next event. Database fencing prevents the old
worker from changing bookkeeping; it cannot retract an external side effect.

A future transport/consumer design must handle per-order sequence and late
arrivals, or provide sink-side fencing where supported. This increment does
not claim strict transport ordering, exactly-once delivery or exactly-once
consumer effects.

### Schema and activation

Flyway V3 extends mutable `outbox_delivery` metadata with token, expiry and
retry eligibility, plus constraints and an index. It conservatively backfills
missing delivery rows as pending and adds a deferred reverse foreign key so
every immutable envelope has delivery metadata at commit. Existing publication
timestamps and attempt counts remain intact. Lifecycle facts and immutable
outbox envelopes are not rewritten.

There is no default publisher, background scheduling job or public dispatch
endpoint. The dispatcher is a component for an explicit caller/future adapter;
providing the component alone does not start publication. Any bootstrap wiring
must require a supplied publisher and must not silently discard events.

## Consequences and verification scope

Claims and fences protect delivery metadata while external calls run without
database locks. A failed head intentionally causes per-order head-of-line
blocking. Operators will eventually need backlog/age metrics, alerting,
retry/dead-letter decisions, retention and safe recovery controls.

Unit and PostgreSQL tests must cover ownership, expiry, retries, rollback,
concurrent workers, blocked lock waits, interruption, crash windows and immutable
history. Their existence is not passing evidence; verification is a separate
record. See [delivery behaviour](../outbox-delivery.md) for the operational
contract and failure cases.
