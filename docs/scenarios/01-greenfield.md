# Scenario 1: greenfield URL shortener

- **Status:** Phase 1 foundation accepted on September 17, 2026; Phase 2A creation accepted; Phase 2B redirects and Phase 3 performance measurement implemented, awaiting human review. Full service unfinished.
- **Goal:** create or reuse short links and redirect visitors to fixed destinations. The initial request-ID design was revised to URL-only creation; see [brownfield evidence](02-brownfield.md).
- **Constraints:** easy local setup, one application/database, small reviewed changes and no work beyond the agreed increment.

## Task sequence

1. Establish startup, table setup and isolated tests - Phase 1 complete and accepted.
2. Implement the approved create-only contract and validation checks — accepted.
3. Add redirection after a working creation baseline — implemented, pending human review.
4. Measure the selected local redirect workload — Phase 3, with [methodology and results](../testing.md#redirect-performance-baseline).
5. Verify local checkout reproduction — committed baseline and copied Phase 3 snapshot passed; [evidence and limits](../testing.md#checkout-reproduction).
6. Implement the newly authorized analytics increment and verify failure isolation; see [scenario 3](03-ambiguous.md).
7. Complete final delivery review and, after separate authorization, verify the committed result in hosted CI.

## Execution and validation

- **AI work:** built the foundation, then creation in test-first slices: first success, replay/conflict, concurrent writes, code collisions and validation; added restart/outage checks. Phase 2B adds code lookup, destination header encoding, HEAD and safe failure behavior in the existing module.
- **Human decisions:** approved the foundation, then the create-only acceptance checks, URL rules, error format, five-attempt limit and public testing boundaries. Accepted Phase 2A with “looks good,” then authorized the supplied Phase 2B plan.
- **Result:** startup, constraints, creation and redirect checks passed in the final clean build; see [testing](../testing.md#current-checks-and-results).
- **Phase 3 scope:** the user authorized the supplied performance plan: 1,000 saved links, ten persistent clients, 15 seconds of warm-up and one 60-second measurement. Codex added a separate Maven profile, report calculations and request-level evidence; the app, schema, runtime dependencies and CI were preserved. Target: zero errors and at least 95% within 100 ms, excluding destination loading. See [the observed run](../testing.md#observed-run).
- **Phase 3 result:** the single valid measured run passed with 870,093 valid redirects, no errors/timeouts and 100% within 100 ms. An earlier invocation failed before application startup due to a test-classpath override; its evidence was retained and the harness corrected before measuring. The final regression gate passed 44 unit + 81 integration cases. No application tuning or repeated measurement to seek a pass.
- **Reproduction result:** a clean local clone of `9ba8b54` passed 37 unit + 81 integration cases. After copying and hash-checking the nine pending Phase 3 files, a fresh build passed 44 unit + 81 integration cases. Both retained formatting/static-analysis gates. At that check, Phase 3 was uncommitted and hosted CI was unverified. Both hosted jobs later passed at `87aa283`; new-machine setup remains unverified.
- **Analytics increment:** the user pulled analytics forward and authorized its plan. Stats, bounded best-effort recording and failure isolation are implemented; see [scenario 3](03-ambiguous.md) and [verification](../testing.md#analytics-verification).
- **Not yet demonstrated:** production performance or the complete service. Expiration and API-key protection remain deferred.

Contract: [architecture](../architecture.md#redirect-contract). Decisions: [AI-human log](../ai-log.md).
