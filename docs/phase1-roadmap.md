# Phase 1 implementation roadmap

The first increment establishes the Maven scaffold, pure Java domain and
lifecycle tests. The steps below remain implementation work. They are ordered
to make each increment independently reviewable and verifiable.

1. **Application commands and idempotency contract.** Define typed submission,
   lookup, listing and cancellation operations. Scope submission keys to an
   account, canonicalise semantically equivalent decimal values before
   fingerprinting, and distinguish identical retries from conflicting payloads.
   Inject ID generation and clocks. Add Mockito only when testing concrete
   interactions through application ports provides value.
2. **PostgreSQL persistence and Flyway.** Introduce reference data, orders,
   version-checked writes, submission-key uniqueness and append-only event
   tables. Save an accepted order and its initial lifecycle events in one
   transaction. Do not substitute a Java pre-check for a database uniqueness
   constraint. Define safe aggregate rehydration and keep persistence entities
   outside the domain.
3. **Transaction and race integration tests.** Use PostgreSQL through
   Testcontainers for simultaneous identical submissions, key/payload
   conflicts, stale updates, rollback when event insertion fails, and
   coordinated cancellation/fill races. Verify both possible commit orderings,
   one successful write for a shared expected version, preserved earlier fills
   and no events from a rolled-back loser. A retry must reload and revalidate
   business semantics; a partial fill followed by cancellation can both succeed
   when applied in sequence against fresh versions.
4. **REST and OpenAPI.** Add submission, retrieval, bounded listing and
   cancellation DTOs, stable error responses and documented idempotency and
   concurrency behaviour. Add HTTP contract tests and runnable API examples.
   No public execution-simulation endpoint is required for Phase 1.
5. **Local operations.** Add Docker Compose for the application and PostgreSQL,
   migrations on startup, health checks and complete local setup examples.
   Keep credentials out of source control. Run the full Maven verification
   lifecycle and the documented local flow before claiming they work.
6. **Delivery preparation.** Establish a durable outbox boundary with stable
   event identities and ordering. Document pending delivery, retries and
   idempotent consumers. Kafka and Redis remain unintegrated; do not claim
   cross-system exactly-once delivery.

Follow-up work after Phase 1 includes risk decisions, execution simulation,
durable event publication, authentication and account authorisation,
pagination/filter expansion, observability and history-volume handling. No
real broker/exchange connectivity, exchange latency benchmark, settlement or
production-readiness claim is implied by this portfolio project.
