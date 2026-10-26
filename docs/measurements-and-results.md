# Measurements and results

How redirect performance was measured, what the runs showed and how the figures were calculated. For general setup and testing commands, see [Testing](testing.md).

<a id="redirect-performance-baseline"></a>

## Run the performance check

Complete the testing setup, then run these commands from the repository root:

```bash
# Full regression checks
./mvnw -B -ntp clean verify

# Separate performance check
./mvnw -B -ntp -Pperformance verify
```

The performance command replaces the normal integration suite with the performance test. Unit tests, formatting and static analysis still run. Ordinary verification and GitHub Actions exclude the performance test.

The check uses the application's default usage transport, Kafka, with a disposable single-broker `apache/kafka` container. To reproduce the earlier in-process runs, select that transport:

```bash
./mvnw -B -ntp -Pperformance verify -Dperformance.analytics.transport=in-process
```

> Save required reports outside `target/` before using `clean`.

Completed measurements write `report.json`, `requests.csv` and `summary.txt` into a unique directory under `target/performance/`. A missed target fails the command and retains its reports. A startup failure may occur before measurement reports exist.

## Measurement method

| Setting | Value or rule |
|---|---|
| Saved links | 1,000 |
| Clients | Ten; each waits for its response before sending another request |
| Warm-up | 15 seconds |
| Measurement | 60 seconds, followed by completion of requests already started |
| Timing | From sending a request through receipt of its complete response or failure |
| Correct response | HTTP 302, expected destination, `Cache-Control: no-store`, empty body |
| Latency target | At least 95% of all attempts within 100 ms, with zero errors |
| Usage-recording check | Allow up to ten additional seconds, then compare expected and saved counts for every link |

Startup, link creation, warm-up and destination-page loading are excluded from latency measurements. Clients do not follow redirects or retry failed requests. Incorrect responses and timeouts remain in the results.

The usage-recording check includes successful redirects from **both warm-up and measurement**. Every link's saved count must match. For the in-process transport, it also requires no dropped or unconfirmed events, no pending events and a running recording worker. For Kafka, every event must be acknowledged by the broker and newly counted by the consumer, with no publish failures, rejected messages, failed writes or unacknowledged events. Skipped duplicates are reported but allowed, because matching counts show none was counted twice.

<a id="observed-run"></a>
<a id="analytics-enabled-performance-result"></a>

## Performance results

Both runs used the workload above and met their applicable targets.

| Measure | Before usage recording | With usage recording |
|---|---:|---:|
| Measurement start, September 17, 2026 | 14:42:06 Central | 16:28:03 Central |
| Base revision, with uncommitted changes | `9ba8b54` | `87aa283` |
| Attempts / correct redirects | 870,093 / 870,093 | 842,581 / 842,581 |
| Errors / timeouts | 0 / 0 | 0 / 0 |
| Attempts within 100 ms | 100% | 100% |
| Median response time | 0.664 ms | 0.682 ms |
| 95th percentile | 0.897 ms | 0.938 ms |
| 99th percentile | 1.457 ms | 1.519 ms |
| Longest response time | 18.271 ms | 30.131 ms |
| Completed attempts per second | 14,501.467 | 14,042.900 |
| Final request completion after the interval | 0.342 ms | 0.496 ms |

Displayed timings are rounded. Reports retain full precision.

For the run with usage recording:

| Measure | Result |
|---|---:|
| Expected events, including warm-up | 1,029,604 |
| Saved events | 1,029,604 |
| Links with mismatched counts | 0 |
| Events dropped because the queue was full | 0 |
| Events dropped during shutdown | 0 |
| Unconfirmed writes | 0 |
| Time to finish recording after HTTP completion | 0.159 seconds |
| Events still queued or being written | 0 |

<a id="kafka-transport-results"></a>

## Kafka transport results

The run used the workload above with the default Kafka transport and met its targets.

| Measure | Kafka transport |
|---|---:|
| Measurement start, October 26, 2026 | 10:06:10 Central |
| Revision and working tree | `c5108c6`, clean |
| Attempts / correct redirects | 836,400 / 836,400 |
| Errors / timeouts | 0 / 0 |
| Attempts within 100 ms | 100% |
| Median response time | 0.695709 ms |
| 95th percentile | 0.943958 ms |
| 99th percentile | 1.14575 ms |
| Longest response time | 12.860375 ms |
| Completed attempts per second | 13,939.902 |
| Final request completion after the interval | 0.421083 ms |

| Measure | Result |
|---|---:|
| Expected events, including warm-up | 1,016,423 |
| Saved events | 1,016,423 |
| Links with mismatched counts | 0 |
| Time to finish recording after HTTP completion | 0.00132825 seconds |
| Publisher: submitted / acknowledged by the broker / publish failures | 1,016,423 / 1,016,423 / 0 |
| Consumer: received / newly counted | 1,016,423 / 1,016,423 |
| Consumer: skipped duplicates / rejected messages / failed writes | 0 / 0 / 0 |

The in-process runs used different Java and macOS versions (see [Evidence and limits](#evidence-and-limits)), so this is not a controlled comparison.

## Calculations

**Percentage within 100 ms**

```text
Attempts taking at most 100 ms ÷ all attempts × 100
842,581 ÷ 842,581 × 100 = 100%
```

Fast incorrect responses can enter that percentage, so passing also requires zero errors.

**Percentiles**

Sort all attempt durations from shortest to longest. For the 95th percentile, select position `0.95 × number of attempts`, rounded upward.

For example, with 20 attempts, select the 19th duration. The median uses the same method at 50%. Empty results fail the check.

**Completed attempts per second**

Include the time needed to finish requests started before the measurement deadline:

```text
Attempts ÷ (measurement seconds + final completion seconds)
842,581 ÷ (60 + 0.000496375) ≈ 14,042.900 per second
```

**Usage-count reconciliation**

```text
Successful warm-up redirects + successful measured redirects
187,023 + 842,581 = 1,029,604 expected events

Expected events − saved events
1,029,604 − 1,029,604 = 0
```

Matching totals alone are insufficient: every link’s count must match, and the recording checks must report no losses or pending work.

## Evidence and limits

Both runs used macOS 27.0 on ARM64, 12 available processors, Eclipse Adoptium Java 21.0.12.1+1-LTS and PostgreSQL 17.11 in Docker.

The Kafka run used a Mac14,13 (Apple M2 Max, 12 processors), macOS 27.0.1 (26A434), Eclipse Adoptium Java 21.0.13+9-LTS, Docker Desktop 4.96.3 with Docker Engine 29.9.2 (VM: 12 CPUs, 8 GiB memory), `apache/kafka:4.2.2` (Kafka client 4.2.1) and `postgres:17.11`.

| Evidence | Location |
|---|---|
| Before usage recording | [Performance report](../target/performance/20260917T194206Z-dc9df218-71df-43b3-9ebc-2e04ca122823/report.json) |
| With usage recording | [Performance report](../target/performance/20260917T212803Z-445238a1-d75b-407a-90c5-6950b9c0d62f/report.json) |
| Kafka transport | [Performance report](../target/performance/20261026T150610Z-16c18b1a-253f-4ed5-837e-319ee4473064/report.json) |

Each report directory also contains per-request data and a text summary. Independent calculations recorded during the original verification matched both reports.

- These results describe one local workload, not production capacity or availability.
- The application and request generator shared a Java process. Clients waited between requests, so the workload did not simulate requests arriving independently.
- The two runs were not a controlled experiment measuring the cost of usage recording.
- Complete recording in one run does not guarantee complete counts during overload, storage failures or abrupt shutdown.
- Generated reports may disappear. These historical results do not verify later revisions.
