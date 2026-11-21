# Outbox dispatch contract

The order transaction persists lifecycle facts, immutable outbox envelopes and
delivery metadata together. The dispatch component adds leased claims, retries
and fenced acknowledgements. It does not install a transport, Kafka, Redis, a
background worker or a public dispatch endpoint. Without a supplied publisher
and explicit caller, records remain pending.

## Message and ownership

`application.outbox.OutboxMessage` carries an immutable `OrderEvent` and schema
version 1. `eventId()` is `orderId:sequence`, retained on every retry. A
`DeliveryClaim` adds a UUID ownership token, positive attempt number and
database-issued lease expiry. These fields describe delivery ownership, not an
execution or a new lifecycle event.

PostgreSQL is the authority for eligibility and expiry. Workers cannot extend
or shorten ownership by adjusting their clocks. The store claims only an
order's earliest unpublished event, using `FOR UPDATE SKIP LOCKED` so concurrent
workers can select other available heads. Later events remain blocked while
the head is leased, waiting for retry or unpublished. There is no global order
across different orders.

## Run behaviour

`OutboxDispatcher.dispatch(runLimit)` accepts a limit from 1 to 100. It claims
one event per iteration, commits the claim, publishes outside a database
transaction, then acknowledges or reschedules in another transaction. It stops
when no eligible event exists or the limit is reached. An injected
`TransactionContext` enforces the boundary: dispatch rejects an active ambient
transaction before claiming and checks again before publication. The Spring
adapter reads actual transaction activity. Callers cannot wrap dispatch in an
outer order transaction and defer the claim commit until after publication.

The result contains:

| Field | Meaning |
| --- | --- |
| `claimed` | Claims obtained during a normally completed run. |
| `published` | Publisher returned normally and acknowledgement committed. This does not prove consumer processing. |
| `rescheduled` | Publication threw an exception and retry scheduling committed under the current lease. |
| `leaseLost` | Acknowledgement or rescheduling could not pass its ownership/expiry fence; an external publication may still have occurred. |

Database failures propagate instead of returning a misleading successful run.
A publisher exception follows the retry path. An `InterruptedException`
reasserts interruption and stops the run; its lease remains until recovery.
Counters are returned only when the run completes normally, not as a durable
business ledger.

Acknowledgement and retry scheduling match event ID, token and attempt and
require the lease still to be valid. The adapter locks the row **before**
sampling database time, so waiting for another transaction cannot authorize an
expired lease using an earlier clock reading. A stale worker cannot erase a
new owner's lease, mark it published, or defer its retry.

## Durations and retries

| Setting | Accepted range |
| --- | --- |
| Lease | Whole milliseconds, 1 second to 5 minutes inclusive. |
| Initial retry delay | Whole milliseconds, 1 ms to 1 hour inclusive. |
| Maximum retry delay | Whole milliseconds, initial delay to 1 hour inclusive. |

The first failed attempt uses the initial delay; further attempt numbers
produce exponential doubling capped at the maximum. Attempts increment on
claim, including crash recovery. There is no jitter, maximum retry count,
dead-letter destination or automatic poison-event bypass. A repeatedly failing
head blocks that order until an explicit future recovery policy resolves it.

A concrete `EventPublisher` must enforce a bounded timeout shorter than the
lease and leave time for acknowledgement. Configure network connection,
publication and confirmation waits accordingly. The dispatcher is synchronous;
it has no executor wrapper and cannot stop a publisher that ignores timeout or
interruption. No real publisher is implemented here, so timeout enforcement is
an adapter contract requiring verification when that adapter is added.

## Failure and recovery cases

| Failure point | Durable state and next action |
| --- | --- |
| Claim transaction rolls back | No ownership committed; another worker may claim the pending event. |
| Worker crashes after claim, before publication | Lease expires; a later worker can claim the same event with a new token/attempt. |
| Publisher fails while lease remains valid | Retry is scheduled using database time plus backoff; later events for that order remain blocked. |
| Publish succeeds, then acknowledgement fails or worker crashes | The sink may already have the event. Expiry/recovery can publish the same ID again. |
| Lease expires during an external call | The worker cannot acknowledge/reschedule after expiry. Another owner may recover the head; the old external call may still finish. |
| Completion waits for a row lock until after expiry | The post-lock database-time check rejects completion, even if the call started before expiry. |
| Database fails during claim or completion | The error propagates; the committed lease, if any, determines later recovery. |
| Publication is interrupted | Interrupt status is restored, the run stops, and expiry permits recovery. |

Duplicate publication is an expected crash-recovery outcome. Consumers must
atomically deduplicate stable event IDs with their business effects. A normal
publisher return means whatever the future adapter's documented transport
acceptance contract establishes; it does not imply an end consumer committed.

## Ordering and immutability limits

Per-order head eligibility is not strict physical arrival ordering. For
example, worker A's first-event publication can remain in flight after expiry;
worker B can recover that event, acknowledge it and publish the next event;
A's delayed call can then arrive at the sink. The database token prevents A
from changing delivery metadata, but cannot prevent its late external effect.
Consumers need duplicate/sequence handling, or the transport needs a verified
fencing protocol, before stronger ordering claims are possible.

V3 changes delivery bookkeeping only: it adds claim/retry fields, conservatively
backfills missing delivery rows, and enforces envelope/delivery completeness
with a deferred reverse foreign key. Existing attempts/publication timestamps
are preserved. Delivery never rewrites order lifecycle facts or creates fills.

This increment makes no strict external-ordering, exactly-once delivery,
cross-system atomicity or production-readiness claim. Future work includes a
real timeout-tested publisher, explicit scheduling, observability, dead-letter
and retention policies, consumer idempotency and sequence reconciliation. See
[ADR 0004](adr/0004-outbox-dispatch.md) for the architectural decision and
[verification](outbox-verification.md) for executed tests and their limits.
