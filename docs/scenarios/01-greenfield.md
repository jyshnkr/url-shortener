# Scenario 1: greenfield URL shortener

- **Status:** Phase 1 foundation accepted on September 17, 2026; Phase 2A creation implemented, awaiting human review. Full service unfinished.
- **Goal:** create or reuse short links and later redirect visitors to fixed destinations. The initial request-ID design was revised to URL-only creation; see [brownfield evidence](02-brownfield.md).
- **Constraints:** easy local setup, one application/database, small reviewed changes and no work beyond the agreed increment.

## Task sequence

1. Establish startup, table setup and isolated tests - Phase 1 complete and accepted.
2. Implement the approved create-only contract and validation checks — ready for review.
3. Add redirection after a working creation baseline.
4. Add the remaining agreed features and checks in separately reviewed increments.

## Execution and validation

- **AI work:** built the foundation, then creation in test-first slices: first success, replay/conflict, concurrent writes, code collisions and validation; added restart/outage checks.
- **Human decisions:** approved the foundation, then the create-only acceptance checks, URL rules, error format, five-attempt limit and public testing boundaries. Phase 2A acceptance is pending.
- **Result:** startup, constraints and creation checks passed; see [testing](../testing.md#current-checks-and-results).
- **Not yet demonstrated:** redirection, analytics or the complete service.

Contract: [architecture](../architecture.md#creation-contract). Decisions: [AI-human log](../ai-log.md).
