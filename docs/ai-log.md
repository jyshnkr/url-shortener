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

- **Authorized:** the user supplied the revision plan and explicitly requested implementation and verification. Existing uncommitted work was the baseline; final acceptance remains pending.
- **AI contribution:** changed the HTTP contract to 201 creation/200 destination reuse, rejected legacy headers, separated `links` responsibilities, and added transactional V2 without modifying V1. The service receives its DAO and validated settings; SQL owns fingerprints and storage-failure translation.
- **Evidence:** failing URL-only and migration checks preceded the implementation. Focused regressions passed; [testing](testing.md#current-checks-and-results) records the final gate. Added concurrency, long Unicode, fingerprint mismatch, legacy migration preservation/rollback and lock-wait checks. [Brownfield scenario](scenarios/02-brownfield.md) records the actual change to the working baseline.
- **Boundaries:** only disposable PostgreSQL test containers were used. Development/archive data, dependencies and prior configuration work were preserved. No staging, commit, push or deployment.
- **Review and disposition:** separate read-only standards and behavior/spec reviews found zero actionable findings. The final clean build passed 24 unit + 66 integration cases, with no failures/errors/skips; diff and local documentation-path checks passed. Implementation is ready for human review; acceptance is not inferred from the implementation request.

## Tomcat patch decision

- **Proposed:** Tomcat 11.0.26 instead of Boot 4.1.1's managed 11.0.24, after checking [Boot versions](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html) and [Apache advisories](https://tomcat.apache.org/security-11.html).
- **Human decision:** requested an explanation first, then approved on September 16, 2026.
- **Tradeoff:** security fixes in exchange for compatibility checks and maintenance.
- **Verified:** resolved Tomcat artifacts at 11.0.26; startup passed. No full security scan is claimed.
- **Follow-up:** remove the override when an adopted Boot release supplies a suitable patched version.
