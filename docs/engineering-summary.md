# Engineering summary

Current snapshot; final summary follows the completed service.

- **Objective:** deliver a URL shortener that another engineer can run, test and understand locally.
- **Approach:** one application and database, built in small AI-assisted changes under human review.
- **Delivered:** Phases 1 and 2A accepted: runnable foundation, versioned table setup and URL-only creation with durable destination reuse.
- **Ready for review:** Phase 2B create-and-follow, automated quality/security checks and Phase 3's separate local redirect performance harness. Hosted quality passed at `9ba8b54`; the security job exposed a report-directory permission error. Its local correction is verified and awaits a push/hosted run. Performance methodology and the observed result are recorded in [testing](testing.md#redirect-performance-baseline).
- **Evidence:** [redirect, creation and migration regression checks](testing.md#current-checks-and-results), including Unicode/long headers, concurrency, HEAD, V1 preservation/rollback, restart redirects, a test-database outage and a silent network stall.
- **Local performance:** the single measured run passed: 870,093 valid redirects, zero errors/timeouts, 100% within 100 ms and p95 0.897 ms. The [recorded baseline](testing.md#observed-run) covers 1,000 saved links and ten clients for 60 seconds; the initial pre-measurement harness failure and its correction are retained there too.
- **Reproduction:** the committed baseline passed in a clean local clone (37 unit + 81 integration cases); the hash-verified pending Phase 3 snapshot passed there too (44 unit + 81 integration cases). Security scans found zero known vulnerabilities across 107 packages and no detected working-tree/history secrets. Existing machine/dependency caches were reused. See [checkout evidence](testing.md#checkout-reproduction).
- **Key tradeoff:** favor valid redirects over complete analytics; retain strict link correctness.
- **Next work:** final delivery review, then verification of the final committed revision in hosted CI after separate commit/push authorization. Expiration and API-key protection are explicitly deferred by the user; deployment remains deferred. Analytics is deferred until after submission and remains an assignment gap.
- **Main risks:** reliance on one database, no public-use protection and unverified new-machine setup. Hosted validation of the security permission correction remains pending. The shared-JVM local performance baseline does not establish maximum capacity or production availability.
- **Next decision:** review the reproduction evidence and final delivery scope. No commit or push is part of this increment.
