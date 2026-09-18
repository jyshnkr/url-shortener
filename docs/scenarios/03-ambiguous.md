# Ambiguous-requirement scenarios

An ambiguous requirement leaves room for different interpretations. These scenarios show how we turned unclear product expectations into agreed rules and checked the resulting behavior.

## 1. Decide what happens when usage recording fails

**Goal:** Turn “availability comes first” into clear rules for redirects and usage statistics.

**Unclear requirement:** We wanted working short links and useful statistics, but needed to decide what should happen when recording statistics becomes slow or fails.

| Decomposition — what needed to be solved | Execution — what we built | Validation — how we checked it |
| --- | --- | --- |
| Clarify which outcome takes priority when recording fails. | Agreed that a valid redirect should continue even if its usage cannot be recorded. Built background recording so the redirect does not wait for the recording write. | Blocked and failed recording deliberately. Confirmed that valid redirects still completed. |
| Define what the usage count represents. | Defined it as recorded requests accepted for redirection, including repeats and automated visitors. Creating links, reading statistics, checking response headers alone and failed lookups do not count. | Tested included and excluded requests and checked their recorded totals. |
| Decide what happens when recording cannot keep up. | Limited the waiting queue. When it fills, new recording events are dropped and reported, while valid redirects continue. | Filled the queue deliberately. Confirmed that redirects succeeded and the recorder reported the dropped event. |
| Decide whether an uncertain write should be retried. | Chose not to retry automatically because the original write might already have succeeded, causing a duplicate count. Reported those events as unconfirmed. | Simulated a failed recording write. Confirmed that it was reported without an automatic retry and that later recording recovered. |
| Define the limits of the availability promise. | Kept destination lookup essential: the service must know where to redirect. If that lookup fails, it returns a clear error rather than an incorrect destination. | Stopped or stalled the test database. Confirmed that requests returned clear errors within the test deadline. |

**Observed results:** In one controlled failure test, all four redirects succeeded. Two events were recorded, one was dropped because the queue was full, and one remained unconfirmed after a recording failure. This demonstrated the agreed tradeoff. See [failure-handling behavior](../architecture.md#failures-and-shutdown).

**Limitations:** Statistics can lag or miss activity and do not represent unique visitors. The latest recorded time refers to the redirect request, not when the background process saved it. Separating recording from redirection does not guarantee availability when the database needed for destination lookup is unavailable.

**Related work:** The [greenfield statistics scenario](01-greenfield.md#2-view-recorded-usage-for-a-short-link) explains the completed feature. This scenario explains how the unclear requirement became specific rules that could be tested.

## 2. Define what “fast redirects” means

**Goal:** Turn a general expectation of fast redirects into a clear requirement we can test.

**Unclear requirement:** “Fast” did not specify a response time, workload or which part of the visitor's journey should be measured.

| Decomposition — what needed to be solved | Execution — what we built | Validation — how we checked it |
| --- | --- | --- |
| Set a measurable response-time target. | Agreed that at least 95% of redirect responses should finish within 100 milliseconds. Added a check against that target. | In the recorded analytics-enabled run, all 842,581 measured redirects finished within 100 milliseconds. |
| Define the conditions under which the target applies. | Used 1,000 saved links and ten clients sending requests for one minute after 15 seconds of warm-up. Each client waits for its response before sending another request. | Checked that setup finished before measurement, all ten clients participated and requests were distributed evenly across the saved links. |
| Decide where measurement starts and stops. | Measured from sending a request until the redirect response was received or the request failed. Excluded startup, warm-up and destination-page loading. | Checked request timings and confirmed that clients did not follow redirects to destination websites. |
| Ensure fast but incorrect responses cannot pass. | Required correct redirect responses and zero request errors. Kept incorrect responses and timeouts in the results. | Tested the calculations with slow responses, fast incorrect responses, timeouts and empty results. Confirmed that these cases were handled correctly. |

**Observed results:** The local run met the agreed target: 842,581 correct redirects, zero request errors and 100% within 100 milliseconds. See [measurement method](../measurements-and-results.md#measurement-method) and [recorded results](../measurements-and-results.md#performance-results).

**Limitations:** This describes one local workload. It does not establish maximum capacity, production response times or how quickly destination pages load.

**Related work:** The [greenfield short-link scenario](01-greenfield.md#1-create-and-follow-short-links) describes the feature. This scenario explains how we defined its speed requirement.

## 3. Define what makes a destination address acceptable

**Goal:** Decide what accepting an address promises—and what remains outside the service's responsibility.

**Unclear requirement:** A “valid address” could mean a correctly formatted web address, an available website or a trustworthy destination. Those are different checks.

| Decomposition — what needed to be solved | Execution — what we built | Validation — how we checked it |
| --- | --- | --- |
| Define the address formats we accept. | Accepted complete HTTP or HTTPS addresses with a website host. Rejected missing values, unsupported formats, credentials within the address and invalid characters. | Tested accepted and rejected addresses and confirmed that invalid input received a clear error. |
| Decide whether to contact the destination before accepting it. | Agreed to validate the address without contacting the website. Creation therefore does not depend on that website responding. | Created links using correctly formatted addresses that did not require a reachable website. Checked that destination validation and creation performed no website lookup. |
| Set boundaries for unusual or lengthy addresses. | Applied a length limit and supported valid international characters while rejecting malformed text. | Tested addresses at the length boundary, overly long addresses, international characters and malformed input. |
| Decide whether validation should rewrite the address. | Preserved accepted destination text without trimming or normalizing it. | Compared submitted and saved addresses, including query details and international characters. Checked that different exact destination strings remained distinct. |

**Observed results:** Accepted addresses were preserved, invalid input was rejected, and creation worked without checking whether the destination website was online. See [testing approach](../testing.md#testing-approach) and [address rules](../architecture.md#creation-contract).

**Limitations:** Acceptance does not prove that a website exists, is available, is safe or will remain unchanged.

**Related work:** The [greenfield scenario](01-greenfield.md#1-create-and-follow-short-links) covers creating and following links. The [brownfield scenario](02-brownfield.md#1-simplify-link-creation-while-preserving-existing-links) explains how exact destination matching determines whether an existing short link is reused.

Decisions and contributions are recorded separately in the [AI-human traceability log](../ai-log.md).
