# Testing

## What to use

- **JUnit and AssertJ:** write checks and verify results.
- **Testcontainers:** start real, disposable PostgreSQL for integration tests.
- **Surefire:** runs `*Test` unit tests. **Failsafe:** runs `*IT` integration tests.
- **Spotless:** consistent Java formatting; `validate` fails on drift. Use `./mvnw spotless:apply` to format intentionally.
- **SpotBugs + Find Security Bugs:** analyze production bytecode during `verify`; all reported priorities fail the build. Narrow reviewed exclusions live in [the filter](../config/spotbugs-exclude.xml).
- `IT` means integration test; unit tests use the `Test` ending.

## How to run

- Install JDK 21 and start Docker. You do not need to start the development database.
- From the project root:

```sh
./mvnw verify                            # All configured tests
./mvnw -Dit.test=FoundationIT verify     # Foundation integration tests
./mvnw -Dit.test=CreateLinkIT,LinkCreationIT,CreationLifecycleIT,DestinationMigrationIT verify
./mvnw -Dit.test=RedirectLinkIT,CreationLifecycleIT verify  # Redirect and lifecycle checks
./mvnw test                              # Formatting and unit tests only
bash scripts/security-checks.sh           # Dependency and secret scans (Bash + Docker + network)
```

- On Windows, use `mvnw.cmd`.
- Read results in `target/surefire-reports/` (unit) and `target/failsafe-reports/` (integration).
- Tests use random ports and their own databases. Schema cases roll back; feature data disappears with its disposable container. The outage test stops only its own test database.

## Automated security and CI

[The security command](../scripts/security-checks.sh) generates a [CycloneDX inventory](https://cyclonedx.github.io/cyclonedx-maven-plugin/makeAggregateBom-mojo.html) from Maven's resolved dependencies, including test scope, then scans it with [OSV](https://google.github.io/osv-scanner/usage/scan-source). The scan covers direct/transitive application and test dependencies, not Maven build-plugin dependencies, JDK or container images. Only dependency identities are submitted to the vulnerability service; application source is not mounted in that container.

[Gitleaks](https://github.com/gitleaks/gitleaks) scans the working tree (including untracked files) and all locally available Git history, with full output redaction and network disabled. Generated `target/` output and raw `.git/` files are excluded only by [the directory-scan config](../config/gitleaks-worktree.toml); history uses [default rules without those path exclusions](../.gitleaks.toml). Public demo credentials remain intentional local configuration. There is no blanket secret or vulnerability baseline.

Any finding or scanner error makes the command fail. Reports are `target/bom.json`, `target/security/osv.json`, `target/security/gitleaks-worktree.json` and `target/security/gitleaks-history.json`; `clean` removes them. Static analysis writes `target/spotbugsXml.xml`. These checks complement the behavioral tests; zero findings is not proof of complete security.

[CI](../.github/workflows/verify.yml) has independent quality and security jobs for PRs, pushes to `main`, manual runs and weekly scans. Actions are pinned to commits and scanners to image digests; jobs have read-only repository permissions, finite deadlines and seven-day report retention. The secret job fetches full history. New advisories can fail a later scan without code changes. Review scanner pins and the narrow SpotBugs exclusions when updating tools; do not suppress new findings just to obtain a pass.

The static-analysis exclusions are limited to intended endpoint inventory markers, URI scheme comparisons after ASCII URI parsing, and constructor-injected DAO references. The null-configuration warning was fixed with an explicit guard and remains covered by the existing public validation tests. No runtime dependency upgrade or endpoint behavior change was needed.

## Current checks and results

- **FoundationIT:** startup, stored fields, unique codes/internal IDs/destinations, required values, case-sensitive code format, fingerprint consistency and database-generated UUIDs.
- **LinkCreationTest:** destination validation, invalid base configuration and safe storage failures.
- **LinkResolutionTest:** malformed codes (including null, empty, Unicode and whitespace) return not found without any DataSource interaction.
- **RedirectLinkIT:** create-and-follow, safe 404, case-sensitive service/HTTP lookup, ASCII/escape preservation, Unicode/decomposed Unicode, maximum-length destinations, unchanged reuse, repeated/eight concurrent redirects and automatic HEAD. Clients explicitly disable redirect following.
- **CreateLinkIT:** URL-only HTTP creation/reuse, exact-string independence, Unicode boundaries and long Unicode URLs, safe JSON errors, rejected legacy headers (including empty/case variants), trusted base and eight concurrent callers.
- **LinkCreationIT:** synchronized destination creation/reuse, independent destinations, forced code collisions, five-attempt exhaustion and recovery.
- **CreationLifecycleIT:** reuse and redirects across restart/base change; safe creation/redirect 503 for a stopped database and a silent network stall, plus safe creation failure for a simulated mismatched fingerprint candidate. Redirect errors assert no-store, no Location and safe retry guidance; outage HEAD is bodyless. Each HTTP request has a ten-second deadline. The stall uses a test-only loopback relay. The fingerprint simulation removes a constraint only inside its own disposable container.
- **DestinationMigrationIT:** populated V1 preservation, known UTF-8 fingerprint, reuse of migrated data, duplicate rollback and bounded lock waits/recovery. Empty databases are migrated through V1/V2 by the API and foundation suites.
- **Revision red checks:** URL-only creation returned 400 before the change; V1 lacked fingerprints and duplicate protection. The first sandbox run was blocked by JVM instrumentation; the authorized rerun reproduced the expected behavior failures.
- **Focused PASS:** `./mvnw -B -ntp -Dit.test=CreateLinkIT#matchingRetryReturnsTheOriginalCreationResult,DestinationMigrationIT verify`; then `./mvnw -B -ntp -Dit.test=CreateLinkIT,LinkCreationIT,FoundationIT,CreationLifecycleIT verify` (24 unit + 63 integration cases in the latter run).
- **Phase 2A final build PASS:** `./mvnw -B -ntp clean verify`, September 17, 2026, 12:26 Central: 24 unit + 66 integration cases, zero failures/errors/skips (Java 21, macOS arm64). `git diff --check` passed; V1 and dependency configuration are unchanged. Separate read-only reviews against the captured pre-revision working tree found zero actionable standards findings and zero actionable behavior/spec findings. Local documentation paths and whitespace checks including untracked source files passed.
- **Phase 2B red checks:** first follow returned 404 instead of 302; malformed service input attempted storage; HTTP errors lacked no-store; Unicode Location was dropped; the longest encoded Unicode header returned 500 under the default 8 KB limit; retry guidance was creation-specific. Each failure was reproduced before its fix. An initial unit run was blocked by sandbox JVM instrumentation; the authorized rerun reached the expected behavior failures. A test fixture length error (2,049 instead of 2,048 code units) was corrected without changing validation.
- **Phase 2B focused PASS:** `./mvnw -B -ntp -Dit.test=RedirectLinkIT,CreationLifecycleIT verify`: 37 unit + 19 integration cases, zero failures/errors/skips.
- **Phase 2B final build PASS:** `./mvnw -B -ntp clean verify`, September 17, 2026, 13:17:50 Central: 37 unit + 81 integration cases, zero failures/errors/skips; BUILD SUCCESS in 34.641 seconds. `git diff --check`, local Markdown paths and whitespace checks including untracked files passed. Dependencies, both migrations and database timeouts are unchanged. Separate read-only reviews against `5e1d8ca` (including all new files) found zero actionable standards findings and zero actionable behavior/spec findings.
- **Quality increment PASS:** `./mvnw -B -ntp clean verify`, September 17, 2026, 13:45:49 Central: formatting, 37 unit + 81 integration cases (zero failures/errors/skips), and zero unsuppressed static-analysis findings; BUILD SUCCESS in 37.319 seconds. The initial format gate failed on 16 files; the initial static gate failed on eight findings before the null guard and reviewed exclusions.
- **Security PASS:** `bash scripts/security-checks.sh` found zero known vulnerabilities across 107 resolved components and no detected secrets in the working tree or two local commits. Isolated synthetic checks returned exit 1 for a fake token and for an inventory containing known-vulnerable Log4j; neither fixture nor vulnerable library was added to the repository.
- **Configuration/review PASS:** actionlint 1.7.12 validated the workflow; ShellCheck and `bash -n scripts/security-checks.sh` passed. Diff, changed/untracked whitespace and local Markdown-path checks passed. Standards review found zero actionable issues. Behavior review found a history-scan path exclusion; splitting directory/history configs fixed it, and re-review found zero remaining issues. The history config rejected a synthetic secret under `target/` (exit 1); this was a path-rule probe, not a new Git commit.
- **CI NOT RUN:** workflow execution requires an authorized push. Hosted CI and clean-checkout reproduction remain unverified.
- Expected outage warnings and a Mockito/Java agent warning appear in build output; they do not indicate test failures.

## Still needed

- Add tests for each separately approved feature; analytics, expiration and access protection remain deferred.
- Review the first hosted CI run; no coverage threshold is selected. Build-tool, JDK and container-image vulnerability scanning are outside this increment.
- Performance measurement is deferred from Phase 2B. Later measure warmed local redirects: 95% within 100 ms, ten concurrent clients, one minute; exclude destination loading.
- Verify the build on a clean checkout and in the shared build service.
