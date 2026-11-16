# ADR 0002: Explicit lifecycle commands, exact decimals and immutable history

Status: Accepted for the first increment.

## Lifecycle

`Order` is an immutable aggregate. Successful commands return a new order
snapshot and append one immutable `OrderEvent`. Earlier snapshots and event
lists remain unchanged. There is no public status setter.

| Current status | Permitted next statuses |
| --- | --- |
| `NEW` | `ACCEPTED`, `REJECTED`, `CANCELLED` |
| `ACCEPTED` | `PARTIALLY_FILLED`, `FILLED`, `CANCELLED` |
| `PARTIALLY_FILLED` | `PARTIALLY_FILLED`, `FILLED`, `CANCELLED` |
| `FILLED` | None |
| `CANCELLED` | None |
| `REJECTED` | None |

The matrix is necessary but not sufficient. Commands also validate their
payloads. For example, another partial fill must increase executed quantity
without reaching the full quantity; only a fill reaching the requested quantity
can produce `FILLED`. Overfills fail. Duplicate execution IDs fail without
appending another event.

Submission creates `NEW`. Acceptance is an OMS decision and creates
`ACCEPTED`; it says nothing about a venue acknowledgement or trade. Both MARKET
and LIMIT orders require later explicit `Execution` input to become partially or
fully filled. The domain never simulates a price or generates a fill itself.

Invalid submission terms fail before an order is created. `REJECTED` represents
a decision on an existing `NEW` order, such as a future risk decision. An
inactive account cannot submit an order. Rejection and cancellation require a
nonblank reason of at most 256 characters after trimming.

## Decimal policy

Prices, quantities and instrument increments use `BigDecimal`, normalised to
scale 8 with a maximum precision of 28. This is intended to align with future
PostgreSQL `NUMERIC(28,8)` columns: at most 20 integer digits and 8 fractional
digits. No floating-point calculations are used.

Normalisation uses `RoundingMode.UNNECESSARY`: values requiring rounding are
rejected. Additional trailing zeroes are allowed because removing them loses no
value. Prices, requested quantities, execution quantities, execution prices,
tick sizes and quantity steps must be positive. Cumulative executed quantity
may start at zero.

The instrument validates exact multiples of its price tick and quantity step.
LIMIT orders require a tick-aligned limit price; MARKET orders must not supply
a limit price. Executions must satisfy the same instrument increments. A BUY
limit execution cannot be above its limit; a SELL limit execution cannot be
below its limit.

The first increment does not calculate notional, average fill price, fees or
cash balances. Multiplication can require a wider precision and scale than
either input; those calculations need a separate documented policy before
implementation. Eight decimal places are an explicit simulation constraint, not
a claim that this policy supports every asset class.

## Identity, time and reference data

Callers supply order IDs, execution IDs and `Instant` timestamps. Events use
the deterministic identity `orderId:sequence`, where sequence starts at 1.
Aggregate `revision()` equals the event count minus one. This revision is not a
database optimistic lock; the future adapter must check its stored version.

Lifecycle recording timestamps cannot precede the previous event timestamp;
equal timestamps are allowed and sequence defines order. An execution's source
timestamp cannot be after its recording timestamp. A delayed execution can
carry an earlier source timestamp. There is no hidden system clock or random
ID generation in the domain. An eventual application service must supply a
consistent clock and define how clock regressions are handled.

`Account` and `Instrument` are immutable reference-data snapshots. An order
retains the reference-data terms supplied at submission. Account authorisation,
balances, reference-data persistence and later reference-data changes are not
implemented here.

## Cancellation and concurrency boundary

Cancellation is a final local action in this simulated system. It preserves
all earlier executions and cumulative executed quantity. Remaining quantity
means requested quantity minus executed quantity; on a cancelled order it is
the unexecuted quantity, not an indication that quantity is still active.

A fill received after local cancellation fails. If a full fill is applied
first, cancellation fails. A partial fill followed by cancellation preserves
that fill. These are sequential domain semantics. Immutability prevents shared
object mutation but cannot resolve two commands saved from the same old
snapshot. PostgreSQL version checks and application-level reload/revalidation
are required to resolve concurrent writers.

This final-cancellation policy is unsuitable for direct venue connectivity,
where a cancel request may race with a trade already executed at the venue.
A future execution adapter needs a distinct cancellation protocol, execution
deduplication and reconciliation before accepting such reports. No real broker,
exchange or FIX connectivity is present.

## History and persistence boundary

Order events include per-order sequence, previous and resulting status,
recording time, cumulative quantity, and type-specific execution or reason.
The domain returns immutable records and unmodifiable lists. It currently
retains and copies full history in memory, which is simple to review but has
linear append and duplicate-lookup costs. This is not a scalable execution
journal or an event-sourced persistence model.

The next persistence increment must atomically save order state and new event
rows, enforce stable event and execution uniqueness, and prohibit history
updates/deletes through the application database role. A durable outbox can
later support publication with retries and idempotent consumers. Neither
database immutability nor event delivery is established by immutable Java
objects alone, and neither is implemented in this increment.
