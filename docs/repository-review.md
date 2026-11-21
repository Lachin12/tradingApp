# Initial repository review

Reviewed baseline: `main` at `8a540b74f4d8765382a1b4f765c38fc04c64f4cd`.

The repository contained only a 12-byte `README.md` with the project name. There
was no Java source, Maven build, database schema, test suite, container setup or
CI configuration to assess. The priorities below identify missing foundations;
they are not claims of defects in an existing OMS implementation. GitHub reported
the repository as private during inspection. Public portfolio visibility is a
separate repository setting.

## Improvements ranked by importance

| Priority | Missing foundation and correctness risk | Required evidence |
| --- | --- | --- |
| P0 — first | Domain lifecycle and decimal rules. Unrestricted status updates or accidental rounding can create fills without executions, overfills or invalid prices. | Pure Java command methods, exhaustive legal/illegal transition tests, market acceptance without fills, exact decimal and tick/step boundary tests. |
| P0 — next | Atomic submission idempotency. A lookup followed by a separate insert permits duplicate orders under concurrent retries. | A scoped unique key, canonical request fingerprint, and order plus initial-event persistence in one PostgreSQL transaction; concurrent duplicate and conflicting-payload integration tests. |
| P0 — next | Optimistic concurrency and cancellation/fill races. Two valid commands against one old snapshot can otherwise overwrite each other. | Version-checked updates, rollback on a stale version, reload and domain revalidation, and coordinated race tests against PostgreSQL. |
| P0 — next | Immutable execution history and atomic lifecycle-event persistence. Mutable history or separately committed events can disagree with order state. | Append-only records, execution-ID uniqueness, per-order event sequence uniqueness, transaction rollback tests and database enforcement of history immutability. |
| P1 | Reliable event delivery. Publishing directly before or after a database commit introduces a failure window. | A durable outbox committed with state changes; later, publisher retries and idempotent consumers. No broker or delivery guarantee is implemented in the first increment. |
| P1 | Meaningful integration coverage. In-memory persistence cannot establish PostgreSQL constraint, transaction or locking behaviour. | JUnit 5 and Testcontainers tests for real PostgreSQL, rollback, uniqueness conflicts and controlled concurrency within individual tests. |
| P1 | Reproducible builds and public documentation. A portfolio needs reviewable setup and honest verification claims. | A pinned Maven wrapper, Java 21 build, CI, decisions, examples, and documented limits. |
| P2 | HTTP adapters, operational instrumentation and future modules. Adding integrations before the invariants are established increases the surface for defects. | REST contract and error tests, bounded metrics labels, and ports introduced alongside concrete use cases. Kafka, Redis, risk evaluation and execution simulation remain later work. |

## Scope of the first increment

The first increment establishes Maven module boundaries, the pure Java domain,
exact decimal validation and immutable lifecycle history. It does not establish
transaction safety, submission idempotency, persistence concurrency or reliable
event delivery. Those properties require the PostgreSQL and application
increments and their integration tests.

See [the modular-monolith decision](adr/0001-modular-monolith.md),
[the lifecycle and decimal decision](adr/0002-order-lifecycle-and-decimals.md),
and [the Phase 1 roadmap](phase1-roadmap.md).
