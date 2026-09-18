# Architecture

## Part 1 — System overview

### Purpose and scope

The service creates reusable short links, redirects visitors to their saved destinations and reports recorded usage.

One Spring Boot application handles these operations. PostgreSQL stores links and usage totals. Expiration, access protection and deployment remain deferred.

### Functional requirements

| Requirement | Expected behavior |
| --- | --- |
| Create a short link | Accept a valid HTTP or HTTPS destination and return a saved short link. Validate the address without contacting the destination website. |
| Reuse an existing link | Submitting the exact same destination returns its existing short link. |
| Follow a short link | Find the saved destination and return a redirect. Preserve the destination and distinguish uppercase from lowercase codes. |
| Report recorded usage | Return a link's recorded redirect count and latest recorded time. An unused link returns zero and no timestamp. |
| Handle invalid requests | Return clear errors for invalid input, unknown links and unavailable storage. |

### Non-functional requirements

| Requirement | Expected behavior |
| --- | --- |
| Correctness | Keep short codes unique and prevent simultaneous creation requests from overwriting links or creating conflicting mappings. |
| Redirect availability | Once destination lookup succeeds, slow or failed usage recording must not prevent the redirect. Recording losses must be observable. |
| Response time | At least 95% of attempts complete within 100 ms, with zero errors, under the agreed local workload described in [Measurements and results](measurements-and-results.md#measurement-method). |
| Persistence | Saved links and successfully recorded usage survive application restarts. Events still waiting in memory may be lost. |
| Bounded work and waiting | Limit database waits, creation retries, recording queues and shutdown waits. Return safe errors without exposing database details. |
| Reproducibility | Another engineer can start the project locally and run its checks using the documented prerequisites and commands. |

### System structure

The request paths below use the primary database pool. Background recording is shown separately under [Redirects and usage recording](#redirect-contract).

```text
   ┌────────────────────────────────────────────────────────────────────────────┐
   │                            Browser / API caller                            │
   └──────────────────────────────────────┬─────────────────────────────────────┘
                                          │ HTTP requests / responses
                                          ↕
┌─────────────────────────────────────────┼────────────────────────────────────────┐
│  URL SHORTENER APPLICATION              │                                        │
│                                         │                                        │
│  ┌──────────────────────────────────────┴─────────────────────────────────────┐  │
│  │                              LinksController                               │  │
│  │                    Routes each request to its operation                    │  │
│  └───────────┬──────────────────────────┬──────────────────────────┬──────────┘  │
│              ▼                          ▼                          ▼             │
│  ┌──────────────────────┐   ┌──────────────────────┐   ┌──────────────────────┐  │
│  │   1. POST: create    │   │   2. GET: redirect   │   │  3. GET: statistics  │  │
│  │ LinkCreationService  │   │LinkResolutionService │   │ LinkAnalyticsService │  │
│  └───────────┬──────────┘   └───────────┬──────────┘   └───────────┬──────────┘  │
│              │                          │                          │             │
│              │                          │                          │             │
│              ▼                          ▼                          ▼             │
│  ┌─────────────────────────────────────────────────┐   ┌──────────────────────┐  │
│  │                    LinkStore                    │   │  LinkAnalyticsStore  │  │
│  │     1. Read / insert links    2. Read a link    │   │  Read link + usage   │  │
│  └────────────────────────┬────────────────────────┘   └───────────┬──────────┘  │
│                           ▼                                        ▼             │
│  ┌────────────────────────────────────────────────────────────────────────────┐  │
│  │                      Primary database connection pool                      │  │
│  └──────────────────────────────────────┬─────────────────────────────────────┘  │
│                                         │                                        │
│                                         │                                        │
└─────────────────────────────────────────┼────────────────────────────────────────┘
                                          │ Request SQL
                                          ▼
   ┌────────────────────────────────────────────────────────────────────────────┐
   │                 PostgreSQL: short_links and link_analytics                 │
   └────────────────────────────────────────────────────────────────────────────┘
```

The three operations share one controller and database pool. Arrows inside the application show calls; results return through the same components. These are parts of one application, not separately deployed services.

Creation and redirect lookup share `LinkStore`, but perform different operations. Statistics reads use `LinkAnalyticsStore` to join the link with its saved usage.

The redirect handler also offers an event to an in-memory queue. The [redirect flow](#redirect-contract) shows this step and the separate background write path. Neither creation nor statistics reads enqueue events.

### Technologies

| Technology | Role |
| --- | --- |
| Java and Spring Boot | Application logic and HTTP request handling |
| Spring JDBC | SQL access |
| PostgreSQL | Saved links and usage totals |
| Flyway | Versioned database migrations |
| Docker Compose | Local database setup |

### Main operations

| Operation | What happens |
| --- | --- |
| 1. Create a short link | Validate the destination. Return its existing short link or save a new one. |
| 2. Follow a short link | Look up the saved destination and return a redirect. Offer a usage event for background recording. |
| 3. Read usage | Return the saved redirect count and latest recorded time. Counts may lag behind requests. |

<a id="choices-tradeoffs-and-risks"></a>

### Design choices and trade-offs

| Choice | Why it fits | Trade-off |
| --- | --- | --- |
| One application and database | Keeps development and operation straightforward | Database failure can stop redirects |
| Database-enforced uniqueness | Prevents simultaneous requests from creating conflicting mappings | Creation depends on successful database access |
| Exact destination matching | Makes reuse predictable and preserves submitted addresses | Different text can produce separate links even when addresses appear equivalent |
| Background usage recording | Redirects do not wait for usage writes | Counts can be delayed or lost |
| Explicit SQL and versioned migrations | Makes storage operations and schema changes visible | Queries and migrations must be maintained alongside the code |

The application binds to the local machine by default. It has no authentication or public-use protection.

## Part 2 — Implementation details

### Responsibilities

| Component | Responsibility |
| --- | --- |
| `LinksController` and `LinkErrorHandler` | Accept requests, construct responses and return safe errors |
| Creation, resolution and analytics services | Apply the rules for creating links, finding destinations and reading usage |
| Validators and code generator | Check inputs and generate random short codes |
| `LinkStore` and `LinkAnalyticsStore` | Read and update PostgreSQL |
| `RedirectRecorder` and `JdbcAnalyticsWriter` | Queue events, combine them into batches and save usage |
| Configuration and Flyway | Connect the components, validate settings and apply database migrations |

### API contracts

| Request | Successful response |
| --- | --- |
| `POST /api/v1/links` with `destinationUrl` | `201` for a new link; `200` for reuse. Returns `code`, `shortUrl`, `destinationUrl` and a `Location` header |
| `GET /r/{code}` | `302` with the destination in `Location`, no response body and `Cache-Control: no-store` |
| `GET /api/v1/links/{code}/stats` | `200` with `code`, `redirectCount`, `lastRedirectedAt` and `Cache-Control: no-store`. The timestamp is UTC, or null when no usage has been recorded |

<a id="creation-contract"></a>

Creation accepts only the destination field. Unknown fields and the old `Idempotency-Key` header are rejected.

Destinations must be absolute HTTP or HTTPS addresses with a host, no embedded credentials, and no raw whitespace or control characters. The length limit is 2,048 UTF-16 code units. Valid Unicode paths are supported.

<a id="persistence-and-migration"></a>

### Stored data

```text
┌────────────────────────────────┐
│          short_links           │
│                                │
│           Short code           │
│  Destination and fingerprint   │
│       Original short URL       │
│ Internal ID and creation time  │
└───────────────┬────────────────┘
                │ Linked by short_code (primary / foreign key)
                │ One link has zero or one usage row
┌───────────────┴────────────────┐
│         link_analytics         │
│                                │
│           Short code           │
│    Recorded redirect count     │
│      Latest recorded time      │
└────────────────────────────────┘
```

Short codes are ten case-sensitive ASCII letters or digits.

A destination fingerprint is a fixed-size SHA-256 value used for indexed lookup. The service also compares the full destination before reuse.

Usage rows are created when events are first saved. A link without a usage row returns a count of zero and a null timestamp.

Flyway applies migrations at startup. Migrations preserve existing links; conflicting destination data causes the reuse migration to stop and roll back without deleting or merging mappings.

<a id="how-it-works"></a>

### Creation and simultaneous requests

```text
POST /api/v1/links → LinksController.create
        │
        ▼
┌──────────────────────────────────────────┐
│           LinkCreationService            │
│         1. Validate destination          │
└───────┬──────────────────────────────────┘
        │
        ├── Invalid ──────────────────────▶ 400
        │ Valid
        ▼
2. LinkStore: find exact saved destination
        │
        ├── Found ────────────────────────▶ 200: reuse saved link
        │ Not found
        ▼
3. Generate code; LinkStore: try insert
        │
        ├── Inserted and committed ───────▶ 201: new link
        │ Uniqueness conflict
        ▼
4. LinkStore: look up destination again
        │
        ├── Found ────────────────────────▶ 200: reuse winner
        │ Not found
        ├── Fewer than five attempts ─────▶ Repeat step 3
        │
        └── Five attempts exhausted ──────▶ 503
```

All creation SQL uses the primary pool and `short_links`. Database failures or a matching fingerprint with a different destination return `503`. The controller returns each outcome to the caller.

Database constraints prevent duplicate codes and destination fingerprints. A conflicting insert does not overwrite an existing mapping. A new mapping is committed before creation succeeds.

The destination is saved without trimming or normalization. The short URL uses the configured base address, not request headers. Existing links retain their saved short URL when that configuration changes.

<a id="redirect-contract"></a>

### Redirects and usage recording

```text
GET /r/{code} → LinksController.redirect
        │
        ▼
┌──────────────────────────────────────────┐
│          LinkResolutionService           │
│              Validate code               │
└───────┬──────────────────────────────────┘
        │
        ├── Invalid code ─────────────────▶ 404 (no database call or event)
        │ Valid
        ▼
LinkStore.findByCode via primary pool → short_links
        │
        ├── Unknown code ─────────────────▶ 404 (no event)
        ├── Database failure ─────────────▶ 503 (no event)
        │ Found
        ▼
┌──────────────────────────────────────────┐
│             LinksController              │
│ Construct 302 with destination Location  │
│    Call RedirectRecorder.record(code)    │
└───────┬──────────────────────────────────┘
        │
        │ Offer event to queue; do not wait for capacity or a database write
        │ Accepted → background flow below; full / closing → drop event
        ▼
Return 302 to caller in either case
        │
        │ If the browser follows Location
        ▼
Browser sends a new request to the destination website
```

The controller encodes international characters for the response header while preserving stored text and existing escapes. The browser contacts the destination; the shortener does not fetch it.

<a id="analytics-contract"></a>

The queue, worker and writer below run in the same application. After an event is queued, background processing and the HTTP response proceed independently; neither waits for the other.

```text
Accepted redirect event (short code + request timestamp)
        │
        ▼
┌──────────────────────────────────────────┐
│     RedirectRecorder in-memory queue     │
│           Up to 10,000 events            │
└───────┬──────────────────────────────────┘
        │ One background worker consumes events
        ▼
┌──────────────────────────────────────────┐
│         RedirectRecorder worker          │
│       Up to 1,000 events per batch       │
│       Up to 250 ms to form a batch       │
│ Combine counts and latest time per code  │
└───────┬──────────────────────────────────┘
        │ Call write(batch)
        ▼
┌──────────────────────────────────────────┐
│           JdbcAnalyticsWriter            │
│       LinkAnalyticsStore.increment       │
│  Separate pool: at most one connection   │
└───────┬──────────────────────────────────┘
        │ Insert / update usage
        ▼
┌──────────────────────────────────────────┐
│        PostgreSQL: link_analytics        │
│       Add counts; keep latest time       │
└───────┬──────────────────────────────────┘
        │
        ├── Write acknowledged ───────────▶ Mark batch confirmed
        └── Write error ──────────────────▶ Mark unconfirmed; no retry
```

The 250 ms setting limits batch formation, not how quickly statistics become visible. A backlog or slow write can delay them further.

The writer owns a separate `LinkAnalyticsStore` instance and connection pool. This separates its connection usage from request handling, but both still depend on the same PostgreSQL database.

Updates add counts atomically and retain the latest event timestamp in UTC. Only successful GET redirects contribute to usage statistics; repeated requests and bots count. Creation, statistics reads and failed redirects do not.

A recorded event means the service accepted a GET for redirection. It does not establish that the browser received the response or visited the destination.

### Reading usage statistics

```text
GET /api/v1/links/{code}/stats → LinksController.stats
        │
        ▼
┌──────────────────────────────────────────┐
│           LinkAnalyticsService           │
│              Validate code               │
└───────┬──────────────────────────────────┘
        │
        ├── Invalid code ─────────────────▶ 404 (no database call)
        │ Valid
        ▼
┌──────────────────────────────────────────┐
│      LinkAnalyticsStore.findByCode       │
│          Read via primary pool           │
│   short_links LEFT JOIN link_analytics   │
└───────┬──────────────────────────────────┘
        │
        ├── No link ──────────────────────▶ 404
        ├── Database failure ─────────────▶ 503
        │ Link exists
        ▼
200: code, saved count and latest recorded time
     No usage row yet → count 0, timestamp null
```

The result returns through the service and controller to the caller. This path only reads saved data; it neither records an event nor waits for queued events to be written.

### Failures and shutdown

| Condition | Behavior |
| --- | --- |
| Invalid creation request | Return a safe `400` error |
| Malformed or unknown short code | Return `404`; malformed codes are rejected before database access |
| Required database operation fails | Return a safe `503` with retry guidance |
| Five code attempts are exhausted | Return `503` |
| Recording queue is full | Drop the new event; continue the redirect |
| A usage write fails | Mark the batch unconfirmed; do not retry because the original write may have committed |
| Application stops normally | Stop admitting events, allow five seconds to drain and bound the recorder's shutdown wait to fifteen seconds |
| Process stops abruptly | Queued events may be lost; saved totals remain |

The fifteen-second limit bounds how long the recorder waits. A stuck database driver may continue cleanup afterward.

Database connections and queries have finite timeouts. These individual limits do not establish a single end-to-end response deadline.

Internal counters track saved, dropped and unconfirmed events. They reset on restart. Only the health endpoint is exposed for operational checks; there is no public metrics endpoint.

General checks belong in [Testing](testing.md). Performance methodology and results belong in [Measurements and results](measurements-and-results.md). Versions and local setup are in the [README](../README.md).
