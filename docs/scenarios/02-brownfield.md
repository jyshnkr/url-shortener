# Brownfield scenarios

Brownfield means changing an existing system. These scenarios cover a change to link-creation behavior and the reorganization of its code during that revision.

## 1. Simplify link creation while preserving existing links

**Goal:** Let someone create or retrieve a short link by submitting only its destination, without losing previously saved links.

| Decomposition — what needed to be solved | Execution — what we built | Validation — how we checked it |
| --- | --- | --- |
| Simplify what someone must provide to create a link. | Removed the requirement to supply a separate request identifier. Creation now requires only the destination address. Requests containing the old identifier receive instructions to remove it. | Tested creation with only a destination. Confirmed that requests using the old identifier receive the expected explanation. |
| Reuse a saved link when the same destination is submitted again. | Added destination lookup before creation. An identical destination returns its existing short link; a different destination receives its own mapping. | Submitted the same destination repeatedly and confirmed that the original link was returned. Checked that different destination strings remained independent. |
| Prevent simultaneous requests from creating duplicate mappings. | Added database rules and creation handling so requests for the same destination settle on one saved link. | Sent simultaneous requests for an identical destination. Confirmed that one mapping was created and the other requests returned that same link. |
| Preserve links already stored in the database. | Added a database update that retains existing link details. If conflicting saved destinations prevent a safe update, it stops without merging or deleting records. | Updated a populated test database and compared the saved records before and after. Introduced conflicting data and confirmed that the update rolled back safely. |
| Keep existing behavior working after the change. | Retained destination validation, short-code protection and clear storage errors. Saved links remain reusable after restart. | Rechecked invalid input, long addresses, international characters, code collisions and database failures. Restarted the application and confirmed that existing links were still reused. |

**Observed results:** Creation, reuse, simultaneous-request, database-update and restart checks passed. See [testing commands](../testing.md#commands).

**Limitations:** Destinations are compared exactly. Addresses that look equivalent but have different text are treated separately. Existing conflicting data requires a decision before the database update can proceed.

**Related work:** The original create-and-follow capability is covered in the [greenfield document](01-greenfield.md#1-create-and-follow-short-links). This scenario explains the later change to its creation behavior.

## 2. Organize existing code into clear responsibilities

**Goal:** Make the link-creation code easier to understand, change and test while preserving its checks and data protections.

| Decomposition — what needed to be solved | Execution — what we built | Validation — how we checked it |
| --- | --- | --- |
| Separate handling requests from making creation decisions. | Organized request handling and link-creation logic into separate parts. One handles incoming requests and responses; the other decides whether to reuse a link or create one. | Checked that creation, reuse and invalid requests still returned the expected results. |
| Keep address-validation rules together. | Put destination-address checks in a dedicated validator, keeping those rules separate from saving links and preparing responses. | Rechecked valid addresses, missing values, length limits and international characters. |
| Keep database operations separate from creation logic. | Placed database lookup, saving and storage-error handling in a dedicated part. Creation logic uses those operations without containing the database instructions itself. | Tested saved-link lookup, simultaneous creation, database failures and preservation of existing mappings. |
| Give short-code generation its own responsibility. | Separated random code generation from the decision to save or retry a link. Tests can supply chosen codes to exercise collisions reliably. | Forced duplicate codes and checked successful recovery, the retry limit and protection of existing links. |
| Make configuration and connections between parts clear. | Gathered configuration and component setup in one place. The creation service receives the storage, settings and code generator it needs. | Checked application startup, invalid configuration and creation using the configured short-link address. |

**Observed results:** The revised creation flow passed its behavior checks. Separate code reviews checked the organization and found no remaining actionable issues. See [testing commands](../testing.md#commands).

**Limitations:** Passing behavior tests supports correctness; it does not prove that future changes will always be easy. The structure still needs to be maintained as features grow.

**Related work:** This reorganization happened during the change to destination-only creation and link reuse described above. The [architecture document](../architecture.md#responsibilities) maps the responsibilities to the code.

This demonstrates **separation of concerns (SoC)** by separating different kinds of work, and **single responsibility (SRP)** by giving each part a clear job.

Decisions and contributions are recorded separately in the [AI-human traceability log](../ai-log.md).
