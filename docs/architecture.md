# Architecture

## Part 1 — System overview

### Purpose and scope

The service creates reusable short links, redirects visitors to their saved destinations and reports recorded usage.

One Spring Boot application handles these operations. PostgreSQL stores links and usage totals. By default, Apache Kafka carries usage events from redirects to the background usage writer; an in-process queue remains selectable. Expiration, access protection and deployment remain deferred.

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
| Persistence | Saved links and successfully recorded usage survive application restarts. With Kafka, events the broker has acknowledged are saved after a restart; events still in the producer's memory buffer may be lost. With the in-process queue, events still waiting in memory may be lost. |
| Bounded work and waiting | Limit database waits, creation retries, recording queues, waits for Kafka and shutdown waits. Return safe errors without exposing database details. |
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

The redirect handler also hands an event to the configured usage transport: Kafka by default, or the in-process queue. The [redirect flow](#redirect-contract) shows this step and the separate background write paths. Neither creation nor statistics reads produce events.

### Technologies

| Technology | Role |
| --- | --- |
| Java and Spring Boot | Application logic and HTTP request handling |
| Spring JDBC | SQL access |
| PostgreSQL | Saved links and usage totals |
| Flyway | Versioned database migrations |
| Apache Kafka and Spring for Apache Kafka | Default transport for usage events between redirects and the usage writer |
| Docker Compose | Local database and Kafka broker setup |

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
| Kafka transport with event-ID deduplication | Acknowledged events survive application restarts and database outages; redelivered copies are not counted twice | Adds a broker to run and monitor; one row per counted event is kept for deduplication |
| In-process queue kept as an option | Runs without a broker and reproduces the earlier measurements | Queued events are lost on abrupt shutdown; a failed write is not retried |
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
| `KafkaRedirectPublisher` and `RedirectEventConsumer` | Kafka transport: publish one event per redirect; read events in batches and save each event ID once |
| `RedirectRecorder` | In-process transport: queue events and combine them into batches |
| `JdbcAnalyticsWriter` | Save usage through its own one-connection pool, for either transport |
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
┌───────────────┴────────────────┐   ┌────────────────────────────────┐
│         link_analytics         │   │   processed_redirect_events    │
│                                │   │                                │
│           Short code           │   │            Event ID            │
│    Recorded redirect count     │   │         Processed time         │
│      Latest recorded time      │   │                                │
└────────────────────────────────┘   └────────────────────────────────┘
```

Short codes are ten case-sensitive ASCII letters or digits.

A destination fingerprint is a fixed-size SHA-256 value used for indexed lookup. The service also compares the full destination before reuse.

Usage rows are created when events are first saved. A link without a usage row returns a count of zero and a null timestamp.

`processed_redirect_events` holds the event IDs the Kafka consumer has counted. It is written in the same transaction as `link_analytics` and has no foreign key to `short_links`. It gains one row per counted event; no retention or cleanup is configured yet.

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
│   Call RedirectRecording.record(code)    │
└───────┬──────────────────────────────────┘
        │
        │ Kafka (default): send the event; wait at most 50 ms for topic metadata or buffer space
        │ In-process: offer the event to the queue; never wait for capacity or a database write
        │ Failures and drops are counted, never thrown to the request
        ▼
Return 302 to caller in every case
        │
        │ If the browser follows Location
        ▼
Browser sends a new request to the destination website
```

The controller encodes international characters for the response header while preserving stored text and existing escapes. The browser contacts the destination; the shortener does not fetch it.

<a id="analytics-contract"></a>

Both transports deliver events to `JdbcAnalyticsWriter`, which owns a separate `LinkAnalyticsStore` instance and a connection pool of at most one connection. This separates its connection usage from request handling, but both still depend on the same PostgreSQL database.

Updates add counts atomically and retain the latest event timestamp in UTC. Only successful GET redirects contribute to usage statistics; repeated requests and bots count. Creation, statistics reads and failed redirects do not.

A recorded event means the service accepted a GET for redirection. It does not establish that the browser received the response or visited the destination.

`shortener.analytics.transport` selects the transport: `kafka` (default) or `in-process`.

<a id="kafka-transport"></a>

#### Kafka transport (default)

The publisher, consumer and writer run in the same application; Kafka sits between them. After the event is handed to the producer, the HTTP response and delivery proceed independently.

```text
Accepted GET redirect (short code)
        │
        ▼
┌──────────────────────────────────────────┐
│          KafkaRedirectPublisher          │
│  New event: random event ID, short code  │
│     and request time (UTC), as JSON      │
│ Send to link-redirects, key = short code │
└───────┬──────────────────────────────────┘
        │ Idempotent producer, acks=all; the request does not wait for the broker
        │ Acknowledged → published; send error or delivery timeout → failed (counted)
        ▼
┌──────────────────────────────────────────┐
│        Kafka topic link-redirects        │
│  Events for one code share a partition   │
└───────┬──────────────────────────────────┘
        │ Consumer group url-shortener-analytics; batches of up to 1,000 events
        ▼
┌──────────────────────────────────────────┐
│          RedirectEventConsumer           │
│    Skip malformed messages (counted)     │
│   JdbcAnalyticsWriter.recordNewEvents    │
└───────┬──────────────────────────────────┘
        │ One transaction on the writer's own pool
        ▼
┌──────────────────────────────────────────┐
│       1. Insert the event IDs into       │
│  processed_redirect_events, skip known   │
│    2. Add counts for new IDs only to     │
│   link_analytics; keep the latest time   │
└───────┬──────────────────────────────────┘
        │
        ├── Committed ────────────────────▶ Commit the batch's Kafka offsets
        └── Error ────────────────────────▶ Roll back; keep offsets; retry the batch every second
```

The request thread waits inside the producer only while topic metadata is unavailable or the producer's buffer is full, and for at most 50 ms (`max.block.ms`). Otherwise, sending appends the event to an in-memory batch and returns. Before the web server starts, the publisher waits up to five seconds for topic metadata; if Kafka is unreachable, startup continues with a warning and events fail until Kafka is reachable.

The broker creates `link-redirects` on first use (topic auto-creation). Local Compose gives new topics three partitions; where auto-creation is disabled, create the topic before starting the application, as the [README](../README.md#start-the-service) shows.

Offsets are committed only after the database transaction commits. A failed write therefore leaves the batch uncommitted; the consumer pauses and retries the same batch every second, so a database outage delays statistics without losing acknowledged events. Statistics can lag by the consumer's batch time plus any backlog.

<a id="delivery-guarantee"></a>

**Delivery guarantee.** Kafka delivers at least once: after a crash, a rebalance or an offset reset, an event can arrive again. Each event ID is saved in the same transaction as its count, so every event the broker acknowledges is counted once in `link_analytics`, provided it is consumed while Kafka still retains it (seven days by default) and its ID row is kept. This is not end-to-end exactly-once. An event is lost, and counted as a publish failure, when the producer cannot send it within 50 ms or the broker does not acknowledge it within the producer's two-minute delivery timeout. Events still in the producer's memory are lost if the process stops abruptly. The local broker keeps one copy of each event and has no volume, so removing its container discards events not yet saved.

#### In-process transport

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
| Kafka is unreachable or topic metadata is unavailable | Each redirect waits at most 50 ms in the producer, then continues; the event counts as a publish failure and is not retried |
| Kafka does not acknowledge an event | The producer retries within its two-minute delivery timeout, then the event counts as a publish failure; the redirect has already been returned |
| Kafka: a usage write fails | Roll back event IDs and counts together; keep offsets uncommitted and retry the same batch every second |
| Kafka: an event arrives again | Its event ID is already saved, so it counts as a duplicate and is not added again |
| Kafka: a message is malformed | Skip it and count it as rejected; retrying cannot make it valid |
| In-process: recording queue is full | Drop the new event; continue the redirect |
| In-process: a usage write fails | Mark the batch unconfirmed; do not retry because the original write may have committed |
| Application stops normally | Kafka: the consumer finishes its current batch within ten seconds, and the producer sends buffered events, waiting at most 30 seconds. In-process: stop admitting events, allow five seconds to drain and bound the recorder's shutdown wait to fifteen seconds |
| Process stops abruptly | Kafka: events in the producer's memory may be lost; uncommitted batches arrive again after restart and are deduplicated. In-process: queued events may be lost. Saved totals remain |

The fifteen-second limit bounds how long the recorder waits. A stuck database driver may continue cleanup afterward.

Database connections and queries have finite timeouts. These individual limits do not establish a single end-to-end response deadline.

Internal counters track submitted, published, saved, duplicate, rejected, dropped, failed and unconfirmed events, as well as failed writes; rate-limited warnings in the log summarize losses and delays. They reset on restart. Only the health endpoint is exposed for operational checks; there is no public metrics endpoint.

General checks belong in [Testing](testing.md). Performance methodology and results belong in [Measurements and results](measurements-and-results.md). Versions and local setup are in the [README](../README.md).
