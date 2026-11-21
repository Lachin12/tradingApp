# ADR 0001: Modular monolith with a pure Java domain

Status: Accepted for the first increment.

Historical scope: this ADR records the original domain/scaffold increment.
Its lifecycle and module decisions still apply. The application, PostgreSQL,
concurrency and outbox work described below as future work is implemented
by the later [ADR 0003](0003-transaction-safety.md); see the current
[verification record](../verification.md) for executed results.

## Context

The simulated OMS needs strict lifecycle and transaction invariants while
remaining small enough to review and run locally. Controllers and persistence
entities must not become the location of trading rules. Distributed services,
Kafka and Redis would add operational and consistency concerns before there is
an independently tested business model.

## Decision

Use one deployable application with Maven modules that express dependency
direction:

```text
oms-bootstrap
  ├── oms-api ────────────────┐
  └── oms-infrastructure ────┤
                             ▼
                       oms-application
                             │
                             ▼
                         oms-domain
```

`oms-domain` contains `Account`, `Instrument`, `Order`, `Execution`, `OrderEvent`
and their invariants. It has no runtime library dependencies and no Spring,
HTTP or persistence annotations. JUnit is a test dependency only.

`oms-application` will own submission, retrieval, listing and cancellation use
cases, plus the ports those use cases need. It depends on the domain, never on
controllers or database adapters. IDs and clocks will be supplied at this
boundary instead of being hidden inside domain operations.

`oms-infrastructure` will implement application ports with PostgreSQL adapters
and Flyway migrations. Persistence rows or entities will be mapped to domain
objects explicitly. Transaction orchestration must encompass order updates and
their durable events. This implementation is not present in the first
increment.

`oms-api` will expose HTTP request/response DTOs and translate application
results and errors. It depends on application contracts and does not depend on
infrastructure. Domain objects are not the public JSON contract.

`oms-bootstrap` is the Spring Boot composition root. It wires the API and
infrastructure adapters into one process. The initial bootstrap has no web or
database adapters.

## Extension points

Introduce ports when a concrete use case needs them, rather than speculative
interfaces for every possible dependency. The intended seams are reference-data
lookup and order storage, risk decisions before acceptance, execution-report
ingestion, and durable event dispatch. A later Kafka adapter can publish outbox
records; Redis can support a measured cache need; an execution simulator can
submit explicit execution reports. None is integrated in this increment.

## Consequences

The compiler and Maven dependencies enforce the main direction between
modules. The domain can be tested without a Spring context or database. Module
boundaries add several small build files, but make responsibilities visible to
reviewers and prevent the API and database adapter from depending on each
other.

The scaffold does not itself prove transactional correctness. The persistence
increment must choose one transaction boundary, implement version-checked
writes and exercise it against PostgreSQL. This system is not a matching engine,
a collection of microservices or an event-sourced implementation.
