# Opt-in execution simulation

This simulator applies explicit execution reports to existing orders. It
supplies no market prices, matching, random fills, scheduling or real venue
connectivity. MARKET acceptance still produces `ACCEPTED` with zero fills;
only a later step changes executed quantity.

## Enable locally

The route is absent by default (`oms.simulation.enabled=false`). Set
`OMS_SIMULATION_ENABLED=true` in your ignored `.env` and restart/recreate the
application. With an existing local `.env`:

```sh
./mvnw -B -ntp clean verify
OMS_SIMULATION_ENABLED=true docker compose up --build -d
curl --fail http://localhost:8080/actuator/health
```

Compose passes the setting to the application. Alternatively export
`OMS_SIMULATION_ENABLED=true` before starting the packaged JAR. Wait for health
`UP`. The simulation route also appears in OpenAPI only when enabled.
Simulation does not start a background worker or fill orders automatically.
Keep this unauthenticated API on localhost.

## Repeatable two-step demo

Prerequisites for the demo script: Bash, `curl` and `jq`, the running enabled
application, and the Flyway demo account/instrument. Run:

```sh
./scripts/demo-simulation.sh
# Optional alternate local base URL:
OMS_BASE_URL=http://localhost:8080 ./scripts/demo-simulation.sh
```

The script uses account `00000000-0000-0000-0000-000000000001`, instrument
`00000000-0000-0000-0000-000000000002` (`DEMO`, USD, tick `0.01`, step `1`),
submission key `portfolio-simulator-v1`, and scenario
`00000000-0000-0000-0000-000000000100`. It creates/retrieves a BUY MARKET order
for `10`, then submits these explicit steps:

| Step | Expected version | Quantity | Price | Source execution time |
| --- | ---: | --- | --- | --- |
| 0 | 0 | `4` | `100.25` | `2026-01-01T09:00:00Z` |
| 1 | 1 | `6` | `100.25` | `2026-01-01T09:00:01Z` |

On a fresh run the order moves from `ACCEPTED` to `PARTIALLY_FILLED` and then
`FILLED`, with version 2. The script replays step 0 with the original expected
version zero and requires current `FILLED` state without extra executions.
Its history check expects two execution events, four lifecycle events total.

Rerunning against the same database uses the same order and execution IDs and
replays completed steps; a partially completed run can resume. The fixed old
source times intentionally exercise the permitted delayed-source-report
policy, so a restart does not change the report payload. The script does not
enable simulation itself: 404 from its step request prints an enable/restart
hint. An order created before that failure remains accepted and can be reused
on the next run. If someone cancels or otherwise changes this demo order, the
script may legitimately fail instead of replacing it or silently generating
new execution identities. Use an explicit new submission key/scenario for a
separate experiment.

## Manual request

Submit a MARKET order through the ordinary order endpoint and copy its ID.
Use the same ID throughout this example:

```sh
ORDER_ID='replace-with-order-response-id'
curl -i "http://localhost:8080/api/v1/simulation/orders/$ORDER_ID/steps" \
  -H 'Content-Type: application/json' \
  -d '{
    "scenarioId":"00000000-0000-0000-0000-000000000100",
    "step":0,
    "expectedVersion":0,
    "quantity":"4",
    "price":"100.25",
    "executedAt":"2026-01-01T09:00:00Z"
  }'
```

Success is HTTP 200 with `{ "executionId": "...", "replay": false, "order":
{ ... } }`. `order` is the ordinary order response, including current version
and exact decimal strings. A successful identical retry has `replay: true` and
`Execution-Replayed: true`. ETag represents the current order version.

Send step 1 with expected version 1, quantity `6` and source time
`2026-01-01T09:00:01Z` to complete the example. Exact step-0 replay still returns
200 with the current `FILLED` order after that. Changing step-0 quantity, price
or source time at microsecond precision returns 409. The unchanged step identity
does not generate a new execution merely because its payload changed.

## Identity, retry and concurrency rules

Execution ID is a deterministic UUID v3 derived from the versioned namespace,
scenario, order and step. It is independent of price, quantity and timestamps.
The scenario can span orders because the order ID participates. Step numbers
0–999 are labels: they do not establish a schedule or require contiguous
execution. Expected version and domain rules determine valid application order.

The core execution-report boundary durably claims each execution ID before
applying a new fill. Receipt, versioned update, lifecycle event and outbox row
share one transaction. Identical retries return the **current** snapshot,
including after later fills or cancellation, before checking whether the old
expected version still matches. Changed payload/another order under that same
execution ID conflicts. A failed transaction leaves no committed receipt claim.

New reports require the latest expected version and valid lifecycle state;
conflicts are not blindly retried. Cancellation races still use compare-and-set
updates. New fills after final local cancellation fail; an exact replay of a
previously committed fill remains a replay and adds nothing.

V4 backfills receipts for existing execution facts, retaining their stored
microsecond payload. It does not edit immutable history. Durable receipts make
replay stable across restarts instead of relying on an in-memory set.

The database requires every receipt to match a committed execution fact; it
does not require every fact written through a raw storage adapter to have a
receipt. Execution adapters must use `reportExecution`, not `OrderStore.update`.
A matching legacy fact without a receipt can acquire one on exact replay.

## Input and scope limits

- Quantity/price are positive exact decimals under `NUMERIC(28,8)` rules and
  must meet instrument step/tick and order limit/remaining-quantity rules.
- Expected version is nonnegative; a new report requires the current value.
- `executedAt` is explicit, truncated to microseconds, within UTC years
  0001–9999, and no later than the recording clock for a new fill. Earlier
  times, including before acceptance, are permitted delayed source times.
- Recording time comes from the injected core clock. Committed exact retries
  are not rejected because a changed recording clock would now disagree.
- Invalid input returns 400, missing order/disabled route 404, and identity,
  version or lifecycle conflicts 409. Unexpected persistence errors propagate
  to the standard server-error contract rather than fabricating a fill.

Prices come from the caller. Domain validation is not venue price validation
or evidence of a market trade. There is no persistent scenario plan, scheduler,
real transport, matching engine, risk engine, authentication or automatic
execution. A future real adapter needs its own venue ID, authorization, timing,
cancellation and reconciliation policies. See
[ADR 0005](adr/0005-execution-simulation.md) for the design decisions and
[verification](simulation-verification.md) for executed checks and their limits.
