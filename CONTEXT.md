# URL Shortening

The language of creating independent short links and understanding their usage. Multiple short links may refer to the same destination.

## Language

**Short link**:
An independently created, shareable link that refers to one fixed destination URL. Its usage is distinct from that of other short links, even when their destinations are identical.
_Avoid_: Destination URL, original URL (when referring to the short link itself)

**Short code**:
The service-assigned identifier that distinguishes a short link within the service. It is part of the short link, not the entire shareable URL or its destination.
_Avoid_: Destination ID, custom alias

**Creation request ID**:
A required caller-supplied identity for one intended short-link creation, reused when retrying that creation. It is distinct from both the shared API key used for access control and the service-generated short code.
_Avoid_: API key, short code, destination URL (as synonyms for the creation request ID)

**Destination URL**:
The URL a short link refers to and directs a visitor toward. The same destination URL may be associated with multiple independent short links.
_Avoid_: Original URL, long URL (prefer the role-based term "destination URL")

**Expiration time**:
An optional cutoff fixed when a short link is created; the link is no longer eligible to redirect once that time is reached. It is distinct from a deadline for deleting the link or its usage history.
_Avoid_: Deletion time, retention deadline

**Redirect count**:
The number of recorded requests accepted for redirection through a particular short link. It can be incomplete during analytics-recording failures and does not represent unique people or confirmed destination-page visits.
_Avoid_: Unique visitors, page views, human clicks

**Last redirected at**:
The time of the most recent recorded request accepted for redirection through a particular short link. It can lag during analytics-recording failures and does not establish when anyone last viewed the destination page.
_Avoid_: Last destination visit, last human visit
