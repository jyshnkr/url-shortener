# Scenario 3: availability versus analytics

- **Status:** requirement clarified; implementation and tests pending.
- **Unclear request:** availability is the main priority - should analytics failure also fail a redirect?
- **Clarification:** the user chose to continue a valid redirect when recording fails.
- **Decision:** record usage separately through a bounded queue; never wait for space, and make recording failures visible.
- **Tradeoff:** counts may lag or miss events. Correct destinations remain mandatory; failed database lookups can still prevent redirects.
- **Task order:** build redirects, agree counting rules and limits, add recording, then test failure isolation.
- **Execution so far:** discussion and design agreement only.
- **Validation planned:** check normal counts, simultaneous updates, slow/failed recording and a full queue; redirects must remain unaffected by recording failures.
