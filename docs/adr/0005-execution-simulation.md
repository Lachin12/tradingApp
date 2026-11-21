# ADR 0005: Explicit simulation steps and durable execution idempotency

Status: Accepted for the opt-in execution-simulation increment. Test and runtime
results are recorded separately after execution.

## Context

MARKET and LIMIT acceptance must remain separate from execution. A portfolio
demo needs a repeatable way to supply later fills without pretending to
connect to a venue or discover market prices. Restarting or retrying a demo
must not fill an order twice. This requires durable execution idempotency at
the application boundary, independently of the simulator.

## Decision

### Application execution receipts

All execution adapters use `OmsService.reportExecution`, which first claims a
stable execution ID in the order transaction. An execution receipt records
order ID, quantity, price and source execution timestamp. Its identity is
global, so using an existing execution ID for another order is a conflict.

Quantity/price normalization and source-timestamp validation happen before
claiming. Source time is truncated to microseconds and must be within UTC years
0001–9999, an explicit trading-application range narrower than PostgreSQL's
range. This prevents an unsupported Java `Instant` from reaching SQL. The
canonical payload determines equivalence; decimal trailing zeros and timestamp
differences removed by microsecond truncation do not create different payloads.

An existing receipt with a matching payload replays the current stored order
without another execution, event, outbox record or version increment. Replay
is checked before expected-version and lifecycle checks, so it remains valid
with the original nonnegative expected version after a later fill or
cancellation. Reusing the ID with changed order, quantity, price or canonical
source timestamp returns a conflict rather than replaying.

A new execution requires the current stored version and a legal domain fill.
Receipt, versioned order update, immutable execution event and outbox record
commit together. Validation, state/version conflict or persistence failure
rolls back the receipt claim; an unsuccessful command does not reserve an ID.
A failed receipt owner therefore cannot prevent a later valid attempt from
claiming it. The domain still rejects duplicate execution IDs if directly
asked to append them; the application recognizes successful retries before
issuing another domain command.

V4 creates immutable execution receipts with deferred coherence constraints
and backfills existing execution facts with their stored microsecond payload.
Each receipt must match its execution fact at commit. There is deliberately no
reverse fact-to-receipt requirement for raw storage writes. Execution adapters
must use the application report boundary; exact replay can acquire a missing
receipt for a matching legacy fact without changing history.
It preserves historical lifecycle facts and outbox envelopes. The migration
allows pre-existing fills to replay after upgrade without rewriting history.

### Simulator and deterministic identity

`ScriptedExecutionSimulator` maps one explicit `SimulationStep` to the normal
execution-report boundary. Each step supplies scenario UUID, step number,
expected version, quantity, price and source timestamp. There is no random
price, matching algorithm, scheduler or hidden market-data dependency.

The injected `SimulationExecutionIds` strategy separates scenario identity from
payload. The default strategy uses Java's name-based UUID v3 over UTF-8:

```text
trading-oms:simulation:v1|<scenario UUID>|<order UUID>|<step>
```

The namespace/version prefix is part of the identity contract. Payload fields
are deliberately excluded: changing the price on a retried step must conflict,
not silently create a new execution ID. The MD5-based UUID is a deterministic
identifier, not a security primitive. A future identity change requires an
explicit namespace/version decision.

Steps range from 0 to 999. Step numbers are labels, not a persisted plan or
scheduler cursor; step 9 may be requested before step 0. Expected version and
order invariants control whether a new fill is legal. One scenario can be
applied to multiple orders because identity includes order ID. Reusing a
scenario/order/step with a different canonical payload is an execution conflict.

### Time and validation

The caller supplies source time; the core application receives an injected
recording clock. New executions cannot have a source timestamp after their
recording timestamp. Earlier timestamps, including before order acceptance,
are allowed by the existing delayed-source-report policy of this simulation.
The simulator does not infer real venue causality or sequencing from them.

An exact committed replay is not revalidated against a changed wall clock.
This preserves replay after restart or clock regression. Shape/range and
canonical-payload validation still apply, and expected version must remain
nonnegative even when its value is no longer current.

Prices and quantities retain the existing exact-decimal, instrument tick/step,
limit-price and overfill rules. A simulation price is supplied by the caller;
validating those local rules does not establish that any venue traded there.

### Opt-in API boundary

Simulation is absent by default. `oms.simulation.enabled` defaults to false
and maps from `OMS_SIMULATION_ENABLED`. Only explicit true registers the
simulator/controller and exposes:

```text
POST /api/v1/simulation/orders/{orderId}/steps
```

Requests contain `scenarioId`, `step`, `expectedVersion`, decimal-string
`quantity`/`price` and an explicit `executedAt`. Success returns HTTP 200 with
`executionId`, `replay` and the current `OrderResponse`. The `Execution-Replayed`
header mirrors replay status and ETag reflects the current stored version.
Disabled routes return 404; malformed input is 400; identity, version or
lifecycle conflicts are 409. The ordinary submission endpoint does not fill an
order and is independent of this feature flag.

There is no public generic broker execution route, automatic execution on
submission, background process or persistent simulation-plan engine.

## Consequences and limits

A repeatable demo can submit a MARKET order, record a partial fill, record the
remaining fill and replay the original step after completion. Fixed IDs and
source times preserve identity across retries and restarts; durable receipts
provide the idempotency evidence rather than a process-local cache.

The opt-in API has no authentication and belongs only in the local simulation.
Real connectivity needs authorization, venue execution identity mapping,
market-data and time policies, cancel acknowledgements, late-fill
reconciliation and session recovery. Risk management, matching, real transport,
Kafka, Redis and settlement remain outside this increment.

Meaningful verification must cover concurrent identical/conflicting execution
reports, rollback, stale-version retries, replay after terminal states, receipt
migration/backfill and the enabled/disabled HTTP contract. No passing test or
runtime result is implied by this ADR. See the [demo guide](../execution-simulation.md).
