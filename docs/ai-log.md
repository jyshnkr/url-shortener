# AI-human collaboration log

Selected decisions from the restart discussion; not a full transcript.

- **Ownership:** the user chose collaborative, incremental implementation. Codex builds each agreed change and stops for review.
- **Scope clarified:** create/redirect comes first; the user deferred analytics until after submission. Other planned features require separately approved increments.
- **Generated:** Codex wrote the foundation setup, migration, tests and docs. The official tool generated the Maven wrapper; the archive supplied reference patterns, not new approval evidence.
- **Test boundary approved:** the user explicitly chose startup and database constraints in isolated PostgreSQL. Results: [testing](testing.md#current-checks-and-results).
- **AI correction:** the health test wrongly required an exact JSON body. The failed run exposed extra health-group fields; Codex changed it to verify status without rejecting those fields.
- **Documentation correction:** the user rejected the verbose rewrite and requested plain instructions, a diagram-led architecture, a practical testing guide and an executive summary. The docs were shortened accordingly.
- **Agent rules approved:** the user approved the combined `AGENTS.md` draft, retaining necessary technical names and keeping learning recaps private. Codex applied the exact wording. Validation: approved-text comparison and referenced-path checks passed; application tests not run (documentation only).
- **Commit approval correction:** Codex misinterpreted the user's request for suggestions and created local commit `ed5c753` without authorization. The user requested only this log correction, leaving the commit unchanged. Nothing was pushed. Ignore rules, wrapper tracking and common secret patterns were checked; tests were not rerun.

## Phase 2A: create-link implementation

- **Approved:** the user accepted the create-only contract, validation/error defaults, five code attempts and HTTP/public-operation test boundaries, then authorized an implementation/test/review loop. No commit, redirect or additional dependency was authorized.
- **AI contribution:** implemented `links/`, configuration and creation tests in failing-test-first slices. A synchronized test exposed duplicate-key failure under concurrent retries; a conflict-safe insert followed by a fresh lookup resolved it. Malformed JSON and unsupported fields now return safe errors.
- **Evidence:** [testing](testing.md#current-checks-and-results) includes real PostgreSQL, forced collisions, concurrent requests, restart replay and storage outage. The initial targeted retry run was blocked by sandbox Docker access; its authorized rerun exposed the actual behavior failure before the fix.
- **Review correction:** the standards review found that query timeout alone did not bound network reads. A silent-stall test failed at ten seconds; finite driver timeouts made it pass. Standards re-review found no remaining actionable issue.
- **Behavior review correction:** a malformed-Unicode concern was reproduced over HTTP (201 instead of 400). Validation now rejects unpaired surrogates while preserving valid Unicode. Focused re-review found no remaining issue.
- **Disposition:** implementation and concise docs are ready for human review, not accepted automatically. No dependency, migration, commit or push was added.

## Phase 2A revision: URL-only creation

- **Authorized:** the user supplied the revision plan and explicitly requested implementation and verification. Existing uncommitted work was the baseline; acceptance was pending at delivery (later disposition below).
- **AI contribution:** changed the HTTP contract to 201 creation/200 destination reuse, rejected legacy headers, separated `links` responsibilities, and added transactional V2 without modifying V1. The service receives its DAO and validated settings; SQL owns fingerprints and storage-failure translation.
- **Evidence:** failing URL-only and migration checks preceded the implementation. Focused regressions passed; [testing](testing.md#current-checks-and-results) records the final gate. Added concurrency, long Unicode, fingerprint mismatch, legacy migration preservation/rollback and lock-wait checks. [Brownfield scenario](scenarios/02-brownfield.md) records the actual change to the working baseline.
- **Boundaries:** only disposable PostgreSQL test containers were used. Development/archive data, dependencies and prior configuration work were preserved. No staging, commit, push or deployment.
- **Review and disposition:** separate read-only standards and behavior/spec reviews found zero actionable findings. The final clean build passed 24 unit + 66 integration cases, with no failures/errors/skips; diff and local documentation-path checks passed. Implementation is ready for human review; acceptance is not inferred from the implementation request.

## Phase 2B: follow short links

- **Human disposition:** the supplied plan records the user's “looks good” as Phase 2A acceptance. Its push is user-reported, not independently verified here. Phase 2B was explicitly authorized for implementation and verification from clean `5e1d8ca`; it remains pending human review.
- **AI contribution:** added resolution in the existing controller/service/DAO boundaries, shared `LinkFailure` with a not-found reason, parameterized code lookup and HTTP-only destination encoding. Creation response fields/statuses and stored destination text remain unchanged.
- **Test-first evidence:** [testing](testing.md#current-checks-and-results) records the failed redirect, validation, error-header, Unicode and retry-guidance checks and focused results. Extended the existing lifecycle suite and reused its test-only stall fixture.
- **Correction:** a maximum-length Unicode redirect exposed Tomcat's default response-header limit. Raised only its response allowance to 32 KB; no dependency, schema or database-timeout change. Corrected an off-by-one test fixture length without weakening destination validation.
- **Boundaries:** only disposable PostgreSQL databases were started/migrated. No development/archive database work, staging, commit, push or deployment. Analytics, expiration, authentication and performance measurement remain deferred.
- **Review and disposition:** the final `./mvnw -B -ntp clean verify` passed 37 unit + 81 integration cases, with zero failures/errors/skips; documentation paths and whitespace checks passed. Separate read-only reviews against `5e1d8ca` (including all new files) found zero actionable standards findings and zero actionable behavior/spec findings. The engineer decides acceptance.

## Automated quality and security checks

- **User direction:** explicitly deferred expiration and API-key protection, asked to prioritize automated quality/security checks, and requested continuation after interrupting a tool call. Existing Phase 2B changes were preserved; no new acceptance, commit or push is inferred.
- **AI contribution:** added pinned Spotless and SpotBugs/Find Security Bugs Maven gates, a resolved CycloneDX dependency inventory, digest-pinned OSV/Gitleaks scans and a least-privilege GitHub Actions workflow. Formatting changed 16 Java files. Static analysis prompted an explicit null guard; seven informational/context-specific findings have narrowly scoped, explained exclusions.
- **Evidence:** [testing](testing.md#current-checks-and-results) records the failing initial checks, clean regression pass and successful dependency/secret scans. Synthetic bad inputs proved both scanners fail on findings. The security scan found no known vulnerable dependencies, so no runtime upgrade was made.
- **Boundaries and disposition:** build-tool additions only; schema, runtime dependencies and development/archive databases are unchanged. Performance and clean-checkout reproduction remain next. Hosted CI is configured but not executed; no staging, commit, push or deployment. Standards review found no actionable issue. Behavior review caught a shared path exclusion that could hide historical secrets under `target/`; separate scan configs fixed it, a synthetic path-rule probe detected the fake secret, and re-review found no remaining actionable issue. Workflow, shell and documentation checks passed; ready for human review.

## Phase 3: measure redirect performance

- **Authorized:** the user supplied the Phase 3 plan and requested implementation and verification from clean `9ba8b54`. The selected workload is 1,000 links, ten concurrent clients and a 60-second interval after 15 seconds of warm-up; zero errors and at least 95% within 100 ms. A missed target must be recorded without application tuning or attempts to obtain a passing rerun.
- **AI contribution:** added an isolated Maven `performance` profile, `RedirectPerformanceIT`, report calculations and seven unit checks. The harness uses disposable PostgreSQL, normal application settings on random loopback, persistent clients, synchronized measurement, monotonic timing through full-body receipt, strict redirect validation and bounded waits/cleanup. Unique report directories retain JSON, per-request CSV and a text summary before the acceptance assertion.
- **Verification:** [testing](testing.md#redirect-performance-baseline) records commands, methodology and the observed result. The first full regression command was blocked by sandbox JVM-agent attachment in the existing Mockito tests; it did not reach integration tests or start a performance measurement. The command was rerun with authorized access. Calculation/report checks passed, including failed-result serialization and CSV escaping.
- **Harness correction:** the first performance command failed before application startup because the profile override removed Boot's inherited Failsafe class-directory setting. The failed classpath and exception demonstrate an invalid harness invocation, not a missed latency target. Codex preserved the original log/reports, restored that test-only setting and repeated validation before measuring. No application tuning was performed.
- **Boundaries:** no application code, API, schema, runtime dependencies, application configuration or CI changes. No development/archive database was started or migrated. No staging, commit, push or deployment; clean-checkout verification remains a separate increment.
- **Observed result:** final regression passed 44 unit + 81 integration cases, formatting and static analysis. The corrected performance command passed 44 unit + one performance case: 870,093 valid redirects, zero errors/timeouts, 100% within 100 ms, p95 0.896542 ms. Independent CSV checks confirmed the summary, balanced code distribution and per-client timing. Exactly one measured interval completed; the earlier classpath failure had no samples.
- **Review and disposition:** separate read-only standards and measurement/spec reviews found zero remaining actionable findings, including re-review of the classpath correction. Documentation paths and whitespace checks passed. Ready for human review; acceptance is not inferred. Clean-checkout verification is the recommended next increment.

## Local checkout reproduction

- **User direction:** after the Phase 3 result and recommendation to verify a clean checkout next, the user said “Cool. Next,” authorizing this verification increment. Existing instructions against staging, commits, pushes and development/archive database work remained in force.
- **AI contribution:** created an independent local clone at detached `9ba8b54`, confirmed clean status/no build output/executable wrapper, and ran the full regression gate. Then copied only the nine pending Phase 3 files, checked SHA-256 hashes and the tracked diff, and ran the gate again. The committed baseline and uncommitted snapshot are explicitly distinguished; no source fixes were needed.
- **Evidence:** [checkout reproduction](testing.md#checkout-reproduction) records both passing builds, the security result and retained logs/manifests. The baseline passed 37 unit + 81 integration cases; the pending snapshot passed 44 unit + 81 integration cases, both with formatting and static analysis. Security scans found zero known vulnerabilities across 107 packages and no detected secrets in the working tree or three local commits. Existing machine/dependency caches were reused; no cold installation or hosted-CI result is claimed. Local documentation paths and whitespace checks passed.
- **Boundaries and disposition:** the original workspace and Phase 3 performance reports were preserved; only verification documentation was updated. No new performance measurement, application/runtime/schema changes, shared database work, staging, commit or push. Ready for human review; final delivery review and separately authorized publication/hosted CI remain next.

## CI security report permissions

- **Diagnosis and authorization:** the user asked to inspect the failed security job. Codex found that quality and dependency checks passed, while both Gitleaks commands failed to write their reports. That turn diagnosed the problem without editing. The user's next push (`f97307c`) included Phase 3 but no script change; after clarifying this, the user explicitly authorized the fix.
- **AI contribution:** added the invoking user's numeric UID/GID to both Gitleaks Docker commands, allowing writes to the runner-owned report directory without restoring root permission-override capabilities or widening directory permissions. All existing scan scope, redaction, network isolation and container restrictions remain unchanged.
- **Evidence:** a disposable Linux-owned directory reproduced the permission failure; matching its owner fixed it. Both actual repository/history scans successfully wrote reports under the same Linux permission conditions. The full local security command passed with zero known vulnerabilities across 107 packages and no detected secrets in the working tree or four local commits. Shell syntax, ShellCheck and whitespace checks passed; regression results are recorded in [testing](testing.md#current-checks-and-results).
- **Disposition:** fix is local and ready for review after validation; hosted CI must run on a push containing the script change. No staging, commit, push, application changes or development/archive database work was performed.

## Tomcat patch decision

- **Proposed:** Tomcat 11.0.26 instead of Boot 4.1.1's managed 11.0.24, after checking [Boot versions](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html) and [Apache advisories](https://tomcat.apache.org/security-11.html).
- **Human decision:** requested an explanation first, then approved on September 16, 2026.
- **Tradeoff:** security fixes in exchange for compatibility checks and maintenance.
- **Verified:** resolved Tomcat artifacts at 11.0.26; startup passed. No full security scan is claimed.
- **Follow-up:** remove the override when an adopted Boot release supplies a suitable patched version.
