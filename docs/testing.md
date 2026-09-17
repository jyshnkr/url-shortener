# Testing

## What to use

- **JUnit and AssertJ:** write checks and verify results.
- **Testcontainers:** start real, disposable PostgreSQL for integration tests.
- **Surefire:** runs `*Test` unit tests. **Failsafe:** runs `*IT` integration tests.
- `IT` means integration test; unit tests use the `Test` ending.

## How to run

- Install JDK 21 and start Docker. You do not need to start the development database.
- From the project root:

```sh
./mvnw verify                            # All configured tests
./mvnw -Dit.test=FoundationIT verify     # Foundation integration tests
./mvnw -Dit.test=CreateLinkIT,LinkCreationIT,CreationLifecycleIT,DestinationMigrationIT verify
./mvnw test                              # Unit tests only
```

- On Windows, use `mvnw.cmd`.
- Read results in `target/surefire-reports/` (unit) and `target/failsafe-reports/` (integration).
- Tests use random ports and their own databases. Schema cases roll back; feature data disappears with its disposable container. The outage test stops only its own test database.

## Current checks and results

- **FoundationIT:** startup, stored fields, unique codes/internal IDs/destinations, required values, case-sensitive code format, fingerprint consistency and database-generated UUIDs.
- **LinkCreationTest:** destination validation, invalid base configuration and safe storage failures.
- **CreateLinkIT:** URL-only HTTP creation/reuse, exact-string independence, Unicode boundaries and long Unicode URLs, safe JSON errors, rejected legacy headers (including empty/case variants), trusted base and eight concurrent callers.
- **LinkCreationIT:** synchronized destination creation/reuse, independent destinations, forced code collisions, five-attempt exhaustion and recovery.
- **CreationLifecycleIT:** reuse across restart/base change; safe 503 for a stopped database, a silent network stall and a simulated mismatched fingerprint candidate. The stall uses a test-only loopback relay. The fingerprint simulation removes a constraint only inside its own disposable container.
- **DestinationMigrationIT:** populated V1 preservation, known UTF-8 fingerprint, reuse of migrated data, duplicate rollback and bounded lock waits/recovery. Empty databases are migrated through V1/V2 by the API and foundation suites.
- **Revision red checks:** URL-only creation returned 400 before the change; V1 lacked fingerprints and duplicate protection. The first sandbox run was blocked by JVM instrumentation; the authorized rerun reproduced the expected behavior failures.
- **Focused PASS:** `./mvnw -B -ntp -Dit.test=CreateLinkIT#matchingRetryReturnsTheOriginalCreationResult,DestinationMigrationIT verify`; then `./mvnw -B -ntp -Dit.test=CreateLinkIT,LinkCreationIT,FoundationIT,CreationLifecycleIT verify` (24 unit + 63 integration cases in the latter run).
- **Final build PASS:** `./mvnw -B -ntp clean verify`, September 17, 2026, 12:26 Central: 24 unit + 66 integration cases, zero failures/errors/skips (Java 21, macOS arm64). `git diff --check` passed; V1 and dependency configuration are unchanged. Separate read-only reviews against the captured pre-revision working tree found zero actionable standards findings and zero actionable behavior/spec findings. Local documentation paths and whitespace checks including untracked source files passed.
- Expected outage warnings and a Mockito/Java agent warning appear in build output; they do not indicate test failures.

## Still needed

- Add tests for each separately approved feature; redirects and analytics are not tested or implemented.
- Add automated code analysis, formatting and security checks; no coverage threshold is selected.
- Measure warmed local redirects: 95% within 100 ms, ten concurrent clients, one minute; exclude destination loading.
- Verify the build on a clean checkout and in the shared build service.
