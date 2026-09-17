# Testing

## What to use

- **JUnit and AssertJ:** write checks and verify results.
- **Testcontainers:** start real, disposable PostgreSQL for integration tests.
- **Surefire:** runs `*Test` unit tests. **Failsafe:** runs `*IT` integration tests.
- **FoundationIT:** current startup and table checks; `IT` means integration test.

## How to run

- Install JDK 21 and start Docker. You do not need to start the development database.
- From the project root:

```sh
./mvnw verify                            # All configured tests
./mvnw -Dit.test=FoundationIT verify     # Foundation integration tests
./mvnw test                              # Unit tests only; none exist yet
```

- On Windows, use `mvnw.cmd`.
- Read integration results in `target/failsafe-reports/`.
- Tests use random ports and their own database. Each schema case rolls back its changes; the container is cleaned up afterward.

## Current checks and results

- Covers real HTTP startup, stored fields, unique codes/request IDs, required values, code format, case sensitivity and shared destinations.
- **Last clean run:** September 16, 2026, 22:58 Central; 19 cases passed, zero failures/errors/skips, on macOS arm64 with Java 21.
- Missing-table and missing-constraint checks failed before the corresponding changes, then passed.
- Local startup and restart also passed. These results do not prove working short-link APIs.

## Still needed

- Agree and add feature, concurrent-request, failure and restart tests.
- Add automated code analysis, formatting and security checks; no coverage threshold is selected.
- Measure warmed local redirects: 95% within 100 ms, ten concurrent clients, one minute; exclude destination loading.
- Verify the build on a clean checkout and in the shared build service.
