# Architectural references and dependency choices

Reference projects reviewed on 2026-11-16; dependency notes updated for the
Phase 1 persistence/API increment on 2026-11-21. These projects informed
independently implemented design principles. No source code was copied.
exchange-core, QuickFIX/J and Spring Kafka are not dependencies. Repository
links are pinned to upstream HEAD commits observed during the original review.

## Spring Petclinic

Source: [README](https://github.com/spring-projects/spring-petclinic/blob/500158f732419217507c7656904b8e6aa1bcc0d6/README.md).

Adopt reproducible Maven wrapper builds, a clear local setup, explicit database
configuration and database integration tests. The OMS separates its domain from
Spring and database adapters rather than adopting a sample CRUD architecture.
Petclinic's default H2 setup is not evidence for PostgreSQL transaction behaviour;
the OMS integration suite uses PostgreSQL itself through Testcontainers.

## Testcontainers Java

Sources: [README](https://github.com/testcontainers/testcontainers-java/blob/e16dca9ea8e8b097c7b15aa8f420cee0a1b16687/README.md),
[PostgreSQL module](https://github.com/testcontainers/testcontainers-java/blob/e16dca9ea8e8b097c7b15aa8f420cee0a1b16687/docs/modules/databases/postgres.md),
[JUnit 5 integration](https://github.com/testcontainers/testcontainers-java/blob/e16dca9ea8e8b097c7b15aa8f420cee0a1b16687/docs/test_framework_integration/junit_5.md).

Use throwaway PostgreSQL instances for migrations, uniqueness constraints,
numeric constraints, rollback and optimistic concurrency tests. Cancellation and
fill race tests should coordinate transactions with latches or barriers, not
timing sleeps. The JUnit extension documentation says parallel test execution is
unsupported: keep test methods sequential while deliberately creating competing
transactions inside a race test.

The upstream main-branch docs show Testcontainers 2.x artifact names such as
`testcontainers-postgresql` and `testcontainers-junit-jupiter`. The selected Spring
Boot 3.5.16 BOM manages **Testcontainers 1.21.4**, whose corresponding artifacts are
`postgresql` and `junit-jupiter`. These are test-scoped dependencies in the
persistence and bootstrap modules; the implementation follows the selected
1.21.4 APIs rather than copying current main-branch examples unchanged.

## exchange-core

Source: [README](https://github.com/exchange-core/exchange-core/blob/2f8548749839e9095c8dc597e4b61521d259fa5d/README.md).

Adopt deterministic business operations, exact arithmetic, explicit distinctions
between command results and trade events, and integrity testing. Immutable
execution records and ordered lifecycle events make decisions inspectable.

This OMS is not a matching engine. It does not adopt exchange-core's Disruptor,
object pooling, thread affinity or lock-free processing. Its benchmark figures
apply to a specific engine workload and exclude network and journaling costs;
they imply nothing about this project. This project makes no latency or
throughput claims without its own reproducible measurements.

## QuickFIX/J

Source: [README](https://github.com/quickfix-j/quickfixj/blob/59bbecb5982e8dfb6ba3e23d771df86d1f426abc/README.md).

Use adapter boundaries to separate protocol/session concerns from business
commands and execution reports. The documented resend and duplicate-message
behaviour reinforces the need for stable execution identifiers and deduplication.

There is no FIX engine, real broker or exchange connectivity in Phase 1.
`ACCEPTED` means local OMS acceptance, not venue acknowledgement or execution.
A future integration must explicitly map protocol messages, recover sessions and
handle duplicates; adding a transport library alone would not provide these
business guarantees.

## Spring for Apache Kafka

Sources: [README](https://github.com/spring-projects/spring-kafka/blob/b6c6d4dbf07a851b8114654c2bd64df47a144a7f/README.md),
[transactions](https://github.com/spring-projects/spring-kafka/blob/b6c6d4dbf07a851b8114654c2bd64df47a144a7f/spring-kafka-docs/src/main/antora/modules/ROOT/pages/kafka/transactions.adoc),
[exactly-once semantics](https://github.com/spring-projects/spring-kafka/blob/b6c6d4dbf07a851b8114654c2bd64df47a144a7f/spring-kafka-docs/src/main/antora/modules/ROOT/pages/kafka/exactly-once.adoc).

Persist order changes and durable lifecycle events in the same PostgreSQL
transaction. A later outbox publisher can deliver committed events with stable
IDs, per-order sequence numbers, retries and idempotent consumers. Keep transport
delivery state separate from immutable business history.

The transaction documentation describes a synchronized secondary commit failing
after the primary transaction commits. Spring transaction synchronization does
not make PostgreSQL and Kafka one atomic resource. Kafka's documented exactly-once
read/process/write sequence does not establish universal exactly-once business
effects. There is no Kafka dependency or publisher in this increment, and durable
event storage must not be described as completed external event delivery.

## Micrometer

Sources: [README](https://github.com/micrometer-metrics/micrometer/blob/9fb166aaf962d628865a4c349f6af0cb12717c08/README.md),
[meter naming and tag cardinality](https://github.com/micrometer-metrics/micrometer/blob/9fb166aaf962d628865a4c349f6af0cb12717c08/docs/modules/ROOT/pages/concepts/naming.adoc).

Keep domain code independent from monitoring libraries. Later application and
infrastructure instrumentation can measure command outcomes, latency, idempotency
conflicts, optimistic conflicts and outbox backlog using bounded tags.

Never put order IDs, account IDs, execution IDs or idempotency keys into metric
tags: their cardinality grows with traffic. Use structured logs or traces for
correlation. Spring Boot Actuator brings Micrometer transitively for runtime
health/observation support. The project adds no metrics backend or custom
trading meters; domain code has no monitoring-library dependency.

## Official documentation and version rationale

The initial domain increment introduced only JUnit Jupiter and the bootstrap
starter. The application and persistence/API increment adds Mockito for port
interaction tests, Spring JDBC, PostgreSQL, Flyway, Testcontainers, Spring MVC,
validation, Actuator and springdoc OpenAPI where they are used. Kafka, Redis,
a risk engine and an execution simulator remain unintegrated extension points.
The domain still has no runtime library dependencies. Unless explicitly pinned
otherwise below, library versions are managed together by the Boot 3.5.16 BOM.

| Choice | Rationale and official source |
| --- | --- |
| Java 21 | User requirement. It is within the supported Java range of [Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html). |
| Spring Boot 3.5.16 | Its [published dependency BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom) manages JUnit Jupiter 5.12.2, matching the requested JUnit 5 stack, and Testcontainers 1.21.4. |
| JUnit Jupiter 5.12.2 | Managed by the selected Boot BOM; see the [official user guide](https://junit.org/junit5/docs/5.12.2/user-guide/). Boot 4.1.2's [BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom) manages Jupiter 6.0.3, so blindly choosing that newer Boot line would change the requested test major version. |
| Mockito 5.17.0 | Boot-managed `mockito-junit-jupiter` provides mocks at application ports, without adding Spring to the application model; see the [published BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom). |
| Testcontainers 1.21.4 | Boot-managed `postgresql` and `junit-jupiter` test modules exercise real PostgreSQL transactions and constraints. Test methods remain sequential; competing transactions are coordinated inside race tests. |
| PostgreSQL 17.12 | The current supported PostgreSQL 17 minor was verified against the [official version table](https://www.postgresql.org/support/versioning/) and [official Docker image manifest](https://github.com/docker-library/official-images/blob/master/library/postgres). Compose and integration tests pin `postgres:17.12-bookworm`. |
| PostgreSQL JDBC 42.7.11 / Flyway 11.7.2 | Both are Boot-managed. Flyway requires its separate `flyway-database-postgresql` module in addition to core; see the [official PostgreSQL support documentation](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database). |
| springdoc OpenAPI 2.8.17 | Explicitly pinned because Boot does not manage it. The [official 2.x compatibility table](https://springdoc.org/v2/faq.html#_what_is_the_compatibility_matrix_of_springdoc_openapi_with_spring_boot) lists Boot 3.5.x with springdoc 2.8.x–2.10.x. The selected [published artifact](https://repo.maven.apache.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/2.8.17/) supplies the Spring MVC OpenAPI document and Swagger UI. |
| Temurin JRE 21.0.13.1_2 | The Docker runtime pins `eclipse-temurin:21.0.13.1_2-jre-jammy`, verified in the [official image manifest](https://github.com/docker-library/official-images/blob/master/library/eclipse-temurin). Maven builds the JAR with a full JDK before Docker packages it. |
| Maven 3.9.16 | Pin the build distribution through Maven Wrapper; see [Maven release history](https://maven.apache.org/docs/history.html). |
| Maven Wrapper 3.3.4 | Pin the wrapper implementation independently from the Maven distribution; see the [official wrapper documentation](https://maven.apache.org/wrapper/) and [published wrapper plugin](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-wrapper-plugin/3.3.4/). |

Versions are explicit selections for Phase 1, not claims that every one is
the newest release. Recheck official release notes and compatibility before
upgrading. Reading reference documentation does not verify this OMS: only build
and test commands actually executed against this repository count as validation.
