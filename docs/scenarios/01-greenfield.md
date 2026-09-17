# Scenario 1: greenfield URL shortener

- **Status:** foundation built; full service unfinished.
- **Goal:** create independent short links and redirect visitors to fixed destinations. Matching retries return the original creation result.
- **Constraints:** easy local setup, one application/database, small reviewed changes and no work beyond the agreed increment.

## Task sequence

1. Establish startup, table setup and isolated tests - implemented; review ongoing.
2. Build creation after foundation acceptance and agreement on its test boundary.
3. Add redirection after a working creation baseline.
4. Add the remaining agreed features and checks in separately reviewed increments.

## Execution and validation

- **AI work:** generated the foundation and tests; corrected the health assertion after a failed run.
- **Human decisions:** approved the test boundary and discussed the security patch before accepting it.
- **Result:** startup and table checks passed; see [testing](../testing.md#current-checks-and-results).
- **Not yet demonstrated:** end-to-end creation, retry handling or redirection.

Initial contract: [architecture](../architecture.md#agreed-api-not-built). Decisions: [AI-human log](../ai-log.md).
