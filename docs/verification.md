# Verification of the first increment

Executed on 2026-11-16, with Temurin JDK 21.0.13, Maven Wrapper 3.3.4,
Maven 3.9.16 and Spring Boot 3.5.16. This records observed results rather than
planned checks.

| Check | Observed result |
| --- | --- |
| `./mvnw -pl oms-domain -am test` | 124 tests, zero failures, zero errors, zero skipped. |
| `./mvnw clean verify` | All six reactor projects succeeded; the same 124 domain tests passed; the executable bootstrap JAR was packaged. |
| `java -jar oms-bootstrap/target/oms-bootstrap-0.1.0-SNAPSHOT.jar` | Spring context started successfully and the non-web application exited with status 0. |
| `git diff --check` | Passed. |
| Maven distribution checksum | Download matched the official SHA-512; wrapper configuration pins SHA-256. |

The cloud workspace initially had a Java 21 runtime without a working development
compiler. A full JDK was downloaded into scratch tooling and its SHA-256 verified
against official Adoptium metadata. Build invocation used that `JAVA_HOME`, a
scratch Maven cache and proxy settings, and the environment's Java CA trust store.
These environment-specific paths and settings are not part of the project.
Initial dependency-resolution and compiler attempts failed before tests ran;
the successful commands above were executed after repairing the tooling.

An independent source review identified two issues before final verification:
extreme decimal exponents could allocate large coefficients before validation,
and direct construction of lifecycle events allowed inconsistent cumulative
quantities. Both were corrected and regression tests were added.

GitHub Actions configuration is included. Local verification alone does not
establish that a hosted workflow has passed; inspect the PR checks separately.
Windows wrapper execution was not tested in this Linux environment.

No PostgreSQL, Testcontainers, REST, transaction, application-submission
idempotency, database race, outbox delivery or benchmark verification was run:
those features are not implemented in this milestone. The cancellation/fill
tests demonstrate sequential domain semantics only. No tests exist yet in the
boundary scaffold modules because they contain no business implementation.
