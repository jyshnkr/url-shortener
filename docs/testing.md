# Testing

## What to use

- **JUnit and AssertJ:** write checks and verify results.
- **Testcontainers:** start real, disposable PostgreSQL for integration tests.
- **Surefire:** runs `*Test` unit tests. **Failsafe:** runs `*IT` integration tests, excluding `RedirectPerformanceIT` unless the `performance` profile is selected.
- **Spotless:** consistent Java formatting; `validate` fails on drift. Use `./mvnw spotless:apply` to format intentionally.
- **SpotBugs + Find Security Bugs:** analyze production bytecode during `verify`; all reported priorities fail the build. Narrow reviewed exclusions live in [the filter](../config/spotbugs-exclude.xml).
- `IT` means integration test; unit tests use the `Test` ending.

## How to run

- Install JDK 21 and start Docker. You do not need to start the development database.
- From the project root:

```sh
./mvnw verify                            # Regression tests, formatting and static analysis
./mvnw -B -ntp -Pperformance verify       # Unit tests + performance IT, not the regression IT suite
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

Both Gitleaks containers run with the invoking user's numeric UID/GID so they can write the host-owned report directory on Linux. Network isolation, read-only root filesystems/repository mounts, dropped capabilities and redaction remain enabled.

Any finding or scanner error makes the command fail. Reports are `target/bom.json`, `target/security/osv.json`, `target/security/gitleaks-worktree.json` and `target/security/gitleaks-history.json`; `clean` removes them. Static analysis writes `target/spotbugsXml.xml`. These checks complement the behavioral tests; zero findings is not proof of complete security.

[CI](../.github/workflows/verify.yml) has independent quality and security jobs for PRs, pushes to `main`, manual runs and weekly scans. Actions are pinned to commits and scanners to image digests; jobs have read-only repository permissions, finite deadlines and seven-day report retention. The secret job fetches full history. New advisories can fail a later scan without code changes. Review scanner pins and the narrow SpotBugs exclusions when updating tools; do not suppress new findings just to obtain a pass.

The static-analysis exclusions are limited to intended endpoint inventory markers, URI scheme comparisons after ASCII URI parsing, and constructor-injected DAO references and the recorder-owned writer boundary. The null-configuration warning was fixed with an explicit guard and remains covered by the existing public validation tests. No runtime dependency upgrade or endpoint behavior change was needed.

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
- **Hosted CI PARTIAL:** [run 35264290081](https://github.com/jyshnkr/url-shortener/actions/runs/35264290081) at `9ba8b54` passed quality checks and found zero dependency vulnerabilities across 107 packages. Both secret scans failed before completion because their containers could not write the runner-owned report directory. The subsequent `f97307c` push still omitted the script fix, and its security job failed too. The local permission correction was verified below; hosted validation was pending at that point and later passed at `87aa283`.
- **Security permission fix PASS (local):** added `--user "$(id -u):$(id -g)"` to both Gitleaks containers. An isolated mode-0755 Linux directory owned by UID/GID 1001 reproduced the original permission error under root with all capabilities dropped; using UID/GID 1001 succeeded. Both repository and history scans then wrote their reports successfully to that Linux-owned disposable directory with all restrictions retained. `bash scripts/security-checks.sh` passed on September 17, 2026, around 15:05 Central: zero known vulnerabilities across 107 packages and no detected secrets in the working tree or four local commits. `bash -n scripts/security-checks.sh`, ShellCheck and `git diff --check` passed.
- **Security-fix regression PASS:** `./mvnw -B -ntp verify` completed September 17, 2026, at 15:06:51 Central in 37.158 seconds: 44 unit + 81 integration cases, zero failures/errors/skips, formatting and zero unsuppressed static-analysis findings. No performance measurement was run; omitting `clean` preserved its existing evidence. Local logs: `/private/tmp/url-shortener-security-user-fix.log` and `/private/tmp/url-shortener-security-fix-verify.log`.
- **Hosted baseline PASS:** [run 35269256408](https://github.com/jyshnkr/url-shortener/actions/runs/35269256408) at `87aa283` passed both quality and security, resolving the report-permission issue. This predates the analytics changes.
- Expected outage warnings and a Mockito/Java agent warning appear in build output; they do not indicate test failures.

## Still needed

- Add tests for each separately approved feature; expiration and access protection remain deferred.
- No coverage threshold is selected. Build-tool, JDK and container-image vulnerability scanning are outside this increment.
- Verify the analytics revision in hosted CI after a separately authorized commit/push.

## Redirect performance baseline

`./mvnw -B -ntp -Pperformance verify` runs `RedirectPerformanceIT` instead of the normal integration suite, while retaining unit tests, formatting and static analysis. Ordinary `verify` and the unchanged CI workflow exclude it. Run the full regression gate first: `./mvnw -B -ntp clean verify`.

The harness starts the application on a random loopback port with its normal settings and disposable PostgreSQL. Application and generator share the test JVM. It first creates 1,000 distinct mappings through HTTP for `https://example.invalid/performance/{index}`, requiring 201 responses and distinct codes. Ten persistent Java HTTP clients each keep one request outstanding, using a shared round-robin sequence across all saved codes. This is a closed-loop workload: each client starts its next request after its preceding request finishes. Redirect following and application retries are disabled; connect/request deadlines remain three/ten seconds, with an additional ten-second bound through full-body receipt.

After a 15-second warm-up and drain, a barrier starts a common 60-second measurement window. Each attempt uses `System.nanoTime()` immediately before sending through full-body receipt or failure. Clients stop starting attempts at the deadline; outstanding requests are included. Every response must be 302 with the expected Location, exactly `Cache-Control: no-store`, and an empty body. Startup, link creation, warm-up and destination loading are excluded. Worker rendezvous, completion and cleanup waits are bounded; the test also has a five-minute deadline and the Maven fork a six-minute limit.

The target passes only with at least one attempt, zero request/response errors and at least 95% of **all** attempts taking at most 100 ms. Incorrect responses and timeouts are retained. The latency percentage includes fast errors, but the separate zero-error requirement prevents them from yielding a pass. Percentiles use nearest rank (`ceil(p × count)`) over all attempt latencies; empty results have null percentiles and fail. Completed requests per second equals all completed attempts divided by 60 seconds plus final drain time; drain is the last attempt completion beyond the measurement deadline.

Before asserting the target, each run writes a console summary, `summary.txt`, `report.json` and `requests.csv` under a unique timestamp/UUID directory in `target/performance/`. CSV includes client/link indexes, monotonic start offset, elapsed nanoseconds, HTTP status, outcome and failure detail; status zero means no complete HTTP response. JSON includes summary statistics, UTC measurement timestamp, Git revision/working-tree state, OS/architecture, processor count, JVM and PostgreSQL versions, and workload settings. A missed target fails the command and retains reports. `clean` removes generated reports, so the observed summary is preserved below.

With analytics enabled, the harness also expects every valid warm-up and measured GET to be persisted under its saved code. After HTTP completion, it allows a separate ten-second analytics drain and performs a bounded database read outside latency measurement. JSON includes `analytics` (expected/persisted totals, per-code mismatches, drain duration/completion, recorder diagnostics and any read failure) and a top-level `passed` requiring both latency and analytics acceptance. Zero queue-full/shutdown drops, zero unconfirmed writes, an empty queue/in-flight batch, a live writer and exact per-code counts are required. A stats-read failure still produces a failed report. Nine calculation/report cases include count mismatches, losses, pending events and a passing redirect result with failed analytics. The older baseline below predates these additional checks.

This measures one selected local workload, not maximum capacity, production availability or performance under independent arrival rates. The shared JVM and local Docker environment affect results. Do not tune the application or rerun until a pass appears; repeat only to correct a demonstrably invalid harness run and retain its original evidence.

### Observed run

- **Calculation/report checks PASS:** seven unit cases cover nearest-rank percentiles, the exact 95%/100 ms boundary, slow responses, fast incorrect responses, request errors, timeouts, empty results, final-drain throughput and failed-report JSON/CSV output. Their synthetic failure fixture prints a `FAIL` summary inside a passing unit test; it does not issue HTTP requests.
- **Regression gate PASS:** `./mvnw -B -ntp clean verify`, September 17, 2026, 14:38:18 Central: 44 unit + 81 integration cases, zero failures/errors/skips, formatting and zero unsuppressed static-analysis findings; BUILD SUCCESS in 38.704 seconds. All six existing integration suites ran; `RedirectPerformanceIT` did not, and no `target/performance/` directory was created. The initial sandbox attempt was blocked by Mockito agent attachment before integration tests; the authorized rerun passed without code changes.
- **Post-correction regression PASS:** the same clean command completed at 14:41:03 Central in 38.506 seconds: 44 unit + 81 integration cases with zero failures/errors/skips, formatting and zero unsuppressed static-analysis findings. The performance test remained excluded. This gate preceded the corrected performance command.
- **Invalid harness invocation FAIL:** the first `./mvnw -B -ntp -Pperformance verify` ended at 14:38:53 Central with `NoClassDefFoundError: UrlShortenerApplication`, before application startup, link creation or measurement. Replacing the Failsafe configuration had removed Boot's inherited `classesDirectory`; the failed XML showed the executable Boot JAR on the classpath instead of `target/classes`. The profile now explicitly retains Boot's setting. Original command log and Failsafe reports are retained in `target/performance/invalid-harness-20260917T193853Z/` (also copied outside `target` before repeating the regression gate). No latency result was obtained or discarded. The repeat corrects this demonstrated harness defect; application code and performance settings are unchanged.
- **Performance PASS:** corrected `./mvnw -B -ntp -Pperformance verify`, September 17, 2026, completed at 14:43:09 Central; BUILD SUCCESS in 1 minute 25 seconds. It ran 44 unit cases and only the one performance integration case, with zero failures/errors/skips, plus formatting/static analysis. Exactly one measured interval completed; there was no target-driven rerun or application tuning.

| Measured result | Observed value |
| --- | ---: |
| Attempts / valid redirects | 870,093 / 870,093 |
| Errors / timeouts | 0 / 0 |
| Attempts within 100 ms | 870,093 (100%) |
| p50 / p95 / p99 | 0.663583 / 0.896542 / 1.456583 ms |
| Maximum latency | 18.270708 ms |
| Completed requests per second | 14,501.467311 |
| Measurement interval / final drain | 60 s / 0.000342125 s |

Measurement began at **2026-09-17T19:42:06.033093Z** (14:42:06 Central). Environment: Mac OS X 27.0, aarch64, 12 available processors; Eclipse Adoptium Java 21.0.12.1+1-LTS, OpenJDK 64-Bit Server VM; PostgreSQL 17.11 (Debian 17.11-1.pgdg13+2, aarch64), image `postgres:17.11`. Revision: `9ba8b549240d8befd6ea753030db2bf0012a5187`, **dirty** working tree containing this uncommitted Phase 3 implementation. Workload: the 1,000-link/ten-client/15-second warm-up/60-second measurement configuration described above.

Raw evidence: `target/performance/20260917T194206Z-dc9df218-71df-43b3-9ebc-2e04ca122823/` contains JSON, CSV, text summary and the command log. The final regression log is `target/performance/regression.log`; the original invalid invocation remains in its separate directory described above. These artifacts are removed by `clean`; this section preserves the observed summary.

Independent CSV recomputation matched counts, latency percentiles, maximum, final drain and throughput. All ten clients contributed; every request started inside the measurement window, each client had no overlapping attempts, and each saved link received 870 or 871 requests. Logs confirmed application/pool shutdown; a read-only Docker check confirmed both disposable performance containers were removed. Separate standards and measurement/spec reviews found no remaining actionable issue after the classpath correction. Whitespace and local documentation-path checks passed. The result is ready for human review, not a production-capacity claim.

## Checkout reproduction

On September 17, 2026, cloned the local repository with `git clone --no-local --no-checkout` into `/private/tmp/url-shortener-reproduction-9c596t4n/checkout`, then checked out detached revision `9ba8b549240d8befd6ea753030db2bf0012a5187`. The checkout was clean, had no `target/`, and retained the Maven wrapper's executable permission. No source-workspace build output, ignored files or development data were copied. This used the existing macOS/JDK/Docker installation and Maven/container caches; it does not establish a cold installation or cross-machine result.

- **Committed baseline PASS:** `./mvnw -B -ntp clean verify` completed at 14:49:23 Central in 37.927 seconds: 37 unit + 81 integration cases, zero failures/errors/skips, formatting and zero unsuppressed static-analysis findings. The checkout remained clean afterward.
- **Pending Phase 3 snapshot PASS:** copied exactly the six modified tracked files and three new performance-test files into that checkout, verifying each file with SHA-256 and matching the tracked diff against the captured source patch. The same clean command completed at 14:50:47 Central in 38.370 seconds: 44 unit + 81 integration cases, zero failures/errors/skips, formatting and zero unsuppressed static-analysis findings. All six regression integration suites ran; the performance run remained excluded. No source changes were needed to reproduce either build.
- **Security PASS:** `bash scripts/security-checks.sh` completed at approximately 14:53 Central against the copied snapshot: zero known vulnerabilities across 107 packages, no detected working-tree secrets and no detected secrets in three local Git commits. The command exited zero; all three JSON scan reports were checked.

At that check, the pending snapshot was intentionally a dirty checkout because Phase 3 had not been committed. These results establish a clean checkout of the committed baseline and a fresh build of the pending files; they do not claim a clean committed checkout of Phase 3. The performance measurement was not repeated, and its original reports remain intact in the source workspace. Only disposable test databases were used; no Compose, development/archive database work, staging, commit or push occurred.

Evidence is retained under `/private/tmp/url-shortener-reproduction-9c596t4n/`: `source-manifest.json`, `source.patch`, `committed-verify.log`, `committed-reports/`, `committed-results.json`, `pending-verify.log`, `pending-results.json` and `security.log`. The isolated checkout retains its latest reports under `target/`. Temporary artifacts can disappear; this section preserves the observed results. Final checks confirmed unchanged application/test code and Maven configuration in the original workspace, unchanged HEAD, an empty staging area, intact original performance evidence and valid local documentation paths. Hosted CI and a final committed Phase 3 checkout remained pending at that point; both hosted jobs later passed at `87aa283`. A fresh local checkout of the upcoming analytics commit remains separate work.

## Analytics verification

The approved increment starts from clean `87aa283`. It adds no runtime dependencies and changes no existing migrations, database timeouts, application properties or CI configuration. Tests use only disposable PostgreSQL; local development/archive databases remain untouched.

- **Test-first checks:** the stats endpoint initially returned 404 instead of 200; after reads were implemented, successful redirects still left the count at zero. Both failures preceded their implementation fixes. Initial checks also caught test-only `SimpleMeterRegistry` cleanup typing and byte-array snapshot equality errors, which were corrected without weakening behavioral assertions. Static analysis caught a dynamically constructed SQL statement; the writer now uses constant parameterized array SQL. Endpoint inventory and constructor ownership exclusions remain narrow and explained.
- **Recorder unit checks:** blocked storage/full queue, failed batch without retries and later recovery, repeated-code batching/max timestamp, partial-batch shutdown drain, backlog discard/admission stop, and a bounded wait for stalled cleanup. Gates use short test-specific limits; production limits remain 10,000 events/1,000 per batch/250 ms/five-second drain/fifteen-second shutdown.
- **LinkAnalyticsIT:** zero/null, correct UTC timestamps, GET-only counting, creation reuse, case-sensitive stats, safe missing/malformed responses without recording, 80 concurrent redirects, safe stats-storage failure, and real analytics-row-lock contention with timeout/recovery while redirects and reads continue.
- **AnalyticsFailureIT:** a controlled stalled writer fills a one-event queue over HTTP. All four GETs return redirects; one event is dropped, one batch is unconfirmed, and the two confirmed events persist after recovery.
- **AnalyticsPersistenceIT:** populated V2-to-V3 preservation/reuse, sparse initial stats and schema constraints, atomic concurrent increments with older timestamps, immediate graceful shutdown/restart retention, and safe stats 503 when disposable PostgreSQL stops.
- **Focused PASS:** `./mvnw -B -ntp spotless:apply -Dit.test=LinkAnalyticsIT verify` passed the ten API cases; `./mvnw -B -ntp spotless:apply -Dit.test=AnalyticsFailureIT,AnalyticsPersistenceIT verify` passed four more integration cases and 52 unit cases, formatting and static analysis. Logs: `/private/tmp/analytics-failure-verified.log`, `/private/tmp/analytics-additional-checks.log`.
- **Review:** separate read-only standards and correctness/spec reviews against `87aa283`, including every new source/test file, found zero actionable code findings. Final verification and documentation are recorded below.

- **Full regression PASS:** `./mvnw -B -ntp clean verify` completed September 17, 2026, at 16:02:08 Central in 56.721 seconds: 52 unit + 95 integration cases, zero failures/errors/skips, formatting and zero unsuppressed static-analysis findings. All nine regression integration suites ran; `RedirectPerformanceIT` did not, and `target/performance/` was absent after this gate. The prior performance evidence was restored from its external copy afterward. Log: `/private/tmp/url-shortener-analytics-regression.log`; XML copies: `/private/tmp/url-shortener-analytics-regression-reports/`.
- **Security PASS:** `bash scripts/security-checks.sh` completed September 17, 2026, around 16:15 Central, with zero known vulnerabilities across 107 packages and no detected secrets in the working tree or four local commits. All three JSON reports were checked. Log: `/private/tmp/url-shortener-analytics-security.log`.

### Analytics-enabled performance result

**PASS:** `./mvnw -B -ntp -Pperformance verify` ran once after the full regression and security gates. It completed at 16:29:07 Central in 1 minute 29 seconds, retaining formatting/static analysis and 52 unit cases; only the one performance integration case ran. No failed invocation, target-driven repeat or application tuning occurred in this increment.

| Result | Observed value |
| --- | ---: |
| Attempts / valid redirects | 842,581 / 842,581 |
| Errors / timeouts | 0 / 0 |
| Attempts within 100 ms | 842,581 (100%) |
| p50 / p95 / p99 | 0.682 / 0.9375 / 1.519084 ms |
| Maximum latency | 30.130916 ms |
| Completed requests per second | 14,042.900491 |
| Measurement / final HTTP drain | 60 s / 0.000496375 s |
| Expected / persisted analytics (warm-up + measurement) | 1,029,604 / 1,029,604 |
| Mismatched codes | 0 |
| Queue-full drops / shutdown drops / unconfirmed writes | 0 / 0 / 0 |
| Analytics drain / permitted drain | 0.158607625 s / 10 s |
| Remaining queued / in flight | 0 / 0 |

Measurement began **2026-09-17T21:28:03.682005Z** (16:28:03 Central), at `87aa2837d473d04c0eb12ea036c5479536eb450d` with this analytics increment uncommitted. Environment: Mac OS X 27.0/aarch64, 12 processors, Eclipse Adoptium Java 21.0.12.1+1-LTS and PostgreSQL 17.11 (Debian/aarch64). Workload remained 1,000 links, ten clients, 15-second warm-up and 60-second measurement. These are local observations, not a controlled causal comparison with the earlier run or a production-capacity claim.

Evidence: `target/performance/20260917T212803Z-445238a1-d75b-407a-90c5-6950b9c0d62f/` contains JSON, per-request CSV, summary, command/regression/security logs and `independent-check.json`. Independent CSV recomputation matched all reported counts, percentiles, maximum, drain and throughput. All ten clients contributed with no overlapping requests per client; all starts fell within the measurement window, and each code received 842 or 843 measured requests. Logs show graceful web shutdown and both pools closing. The earlier pre-analytics performance reports remain alongside this run; `clean` removes all generated artifacts, so this section preserves the observed result.

Final documentation review corrected historical pending-status wording. Standards review: zero remaining actionable findings; correctness/spec review: zero actionable findings. Local documentation paths, tracked and new-file whitespace checks passed. The staging area is empty and HEAD remains `87aa283`; no development/archive database, commit, push or deployment work occurred. Ready for human review; a clean committed analytics checkout and hosted CI remain next after separate authorization.
