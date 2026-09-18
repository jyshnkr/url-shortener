# Greenfield scenarios

Greenfield means building a new product capability. These scenarios cover creating and following short links, and viewing their recorded usage.

## 1. Create and follow short links

**Goal:** Let someone create a short link and use it to reach the intended destination.

| Decomposition — what needed to be solved | Execution — what we built | Validation — how we checked it |
| --- | --- | --- |
| Accept a usable destination address. | Added checks for supported web addresses, missing information, length limits and invalid characters. The service checks the address without contacting the website. | Tested valid and invalid addresses, including long addresses and international characters. Confirmed that accepted addresses were saved without changing their text. |
| Create a short link without replacing an existing one. | Generated random short codes and saved each code with its destination. If a code was already taken, the service tried another within a fixed limit. | Deliberately generated duplicate codes. Checked that creation recovered when possible, returned a clear error when attempts ran out, and never overwrote an existing link. |
| Direct visitors to the correct destination. | Added a lookup that finds the saved destination and tells the browser where to go. Uppercase and lowercase codes remain distinct. | Created links and checked their redirect destinations. Tested repeated requests, simultaneous requests and codes that differ only by letter case. |
| Keep saved links working after a restart. | Stored links in the database so they remain available when the application restarts. | Created links, restarted the application and confirmed that the same links still returned the correct destinations. |
| Handle missing links and database problems clearly. | Added understandable errors for unknown codes and unavailable storage. Limited database waiting times and kept internal details out of error responses. | Tested unknown codes, stopped the test database and simulated an unresponsive connection. Confirmed that requests returned the expected errors within the test deadline. |

**Observed results:** In the recorded local test, all 842,581 redirects completed correctly within 100 milliseconds, with no request errors. The workload used 1,000 saved links and ten clients for one minute after warm-up. See [testing approach](../testing.md#testing-approach) and [measured results](../measurements-and-results.md#performance-results).

**Limitations:** These checks measure the shortener's response, not whether the destination page loaded. The performance result describes the selected local workload, not production capacity.

**Related work:** The later change to reuse a short link for an identical destination is covered in the [brownfield scenario](02-brownfield.md).

## 2. View recorded usage for a short link

**Goal:** Let someone see a short link's recorded redirect count and the time of its latest recorded redirect.

| Decomposition — what needed to be solved | Execution — what we built | Validation — how we checked it |
| --- | --- | --- |
| Make usage information available for each saved link. | Added a statistics response showing the link's code, recorded redirect count and latest recorded time. An unused link starts at zero with no recorded time. | Checked unused links, used links and unknown codes. Confirmed that different links have separate statistics and that unavailable storage produces a clear error. |
| Count the agreed types of activity consistently. | Recorded requests accepted for redirection, including repeated requests and automated visitors. Creating a link, reading statistics, checking response headers alone and failed lookups do not increase the count. | Tested both counted and excluded requests. Confirmed that reusing an existing short link preserves its statistics. |
| Keep valid redirects working when recording is slow or fails. | Recorded usage in the background through a limited waiting queue. When recording cannot keep up, events may be dropped or remain unconfirmed, and those losses are reported. | Deliberately filled the queue, blocked database writes and caused recording failures. Confirmed that valid redirects continued and later recording recovered. |
| Keep counts correct when requests arrive together. | Made count updates add together without overwriting each other. Kept the latest recorded request time even when events arrived out of order. | Sent simultaneous requests and submitted older events after newer ones. Confirmed that counts matched and the latest recorded time did not move backward. |
| Preserve saved links and statistics through database changes and restarts. | Added separate usage storage while retaining existing links. Recorded totals remain in the database after the application stops. | Updated a populated test database and restarted the application. Confirmed that existing links and saved counts remained available. |

**Observed results:** The local load check saved all 1,029,604 expected events from warm-up and measurement. Every link's count matched, with no dropped or unconfirmed events in that run. See [integration-test commands](../testing.md#selected-integration-tests) and [measured results](../measurements-and-results.md#performance-results).

**Limitations:** Counts may arrive late or miss activity during overload, recording failures or abrupt shutdown. They do not represent unique people or prove that a destination page was viewed. Database problems that prevent looking up the destination can still stop redirects.

**Related work:** The decision to prioritize valid redirects over complete statistics is covered in the [ambiguous-requirement scenario](03-ambiguous.md).

Decisions and contributions are recorded separately in the [AI-human traceability log](../ai-log.md).
