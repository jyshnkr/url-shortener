# URL Shortening

The language of creating, reusing and following short links and understanding their usage. Each exact destination string has one service-wide mapping.

## Language

**Short link**:
A shareable link that refers to one fixed destination URL. Submitting the same validated destination string reuses the saved link.
_Avoid_: Destination URL, original URL (when referring to the short link itself)

**Short code**:
The service-assigned identifier that distinguishes a short link within the service. It is exactly ten case-sensitive ASCII letters or digits and is part of the short link, not the entire shareable URL or its destination.
_Avoid_: Destination ID, custom alias

**Creation request ID**:
An internal UUID stored with a mapping. Existing values are retained; PostgreSQL generates new values. It no longer identifies client retries, and the old `Idempotency-Key` header is rejected.
_Avoid_: API key, short code, destination URL (as synonyms for the creation request ID)

**Destination URL**:
The URL a short link refers to and directs a visitor toward. Reuse compares the exact validated string, without trimming, normalization or fetching.
_Avoid_: Original URL, long URL (prefer the role-based term "destination URL")

**Link resolution**:
Read the saved mapping for a valid short code. Resolution does not change the mapping or fetch the destination; unknown and malformed codes are not found.

**Redirect**:
An HTTP 302 response directing the caller to the saved destination through `Location`, with no body and `Cache-Control: no-store`. Non-ASCII characters become UTF-8 percent escapes only in the header; saved text and existing escapes remain unchanged. HEAD returns the corresponding status and headers without a body.

**Creation outcome**:
The saved link plus an indication of whether this call created it or reused it. HTTP maps these outcomes to 201 and 200 respectively.

**Destination fingerprint**:
SHA-256 of the destination’s UTF-8 bytes, used for indexed lookup and uniqueness. A matching fingerprint still requires a full-string comparison before reuse.

**Expiration time**:
An optional cutoff fixed when a short link is created; the link is no longer eligible to redirect once that time is reached. It is distinct from a deadline for deleting the link or its usage history.
_Avoid_: Deletion time, retention deadline

**Redirect count**:
The number of recorded GET requests accepted for redirection through a particular short link, after its 302 response has been constructed. HEAD, creation, stats reads and failed resolution are excluded; repeat requests, retries and bots count. It can be incomplete during analytics-recording failures and does not represent unique people or confirmed destination-page visits.
_Avoid_: Unique visitors, page views, human clicks

**Last redirected at**:
The latest UTC request-recording timestamp among recorded GET redirects for a short link, not the database flush time. It is null until a redirect is recorded. It can lag during analytics-recording failures and does not establish when anyone last viewed the destination page.
_Avoid_: Last destination visit, last human visit
