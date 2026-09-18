# Testing

This guide covers the project’s checks, setup and commands. Performance testing, recorded results and calculations are covered in [Measurements and results](measurements-and-results.md).

<a id="what-to-use"></a>

## Testing approach

Report paths below are relative to `target/`, unless stated otherwise.

| Check | Tools | What it checks | Reports | Failure means |
|---|---|---|---|---|
| Unit tests | JUnit, AssertJ, Mockito, Awaitility, Surefire | Individual logic and edge cases | `surefire-reports/` | An unexpected result or test error |
| Integration tests | Spring Boot, Testcontainers, Failsafe | HTTP behavior, PostgreSQL, migrations, concurrency and recovery | `failsafe-reports/` | A behavior mismatch or setup failure |
| Formatting | Spotless | Consistent Java formatting | Console output | Files need formatting |
| Static analysis | SpotBugs, Find Security Bugs | Potential code defects | `spotbugsXml.xml` | A reported issue needs review |
| Dependency scan | CycloneDX, OSV-Scanner | Known dependency vulnerabilities | `bom.json`, `security/osv.json` | A vulnerability or scanner error |
| Secret scans | Gitleaks | Exposed secrets in files and Git history | `security/gitleaks-*.json` | A detected secret or scanner error |

Tests cover creation and reuse, address validation, redirects, usage recording, restarts and database failures. Unit tests also check performance-report calculations.

Test files are under `src/test/java/`: `*Test` for unit tests and `*IT` for integration tests.

For a failed run, start with the console message and open the corresponding report. A startup failure may prevent tests from reaching application behavior. Expected outage warnings alone do not mean a test failed.

## Setup

1. Install Java 21 and Docker.
2. Start Docker.
3. Open a terminal in the repository root.
4. Check that both are available:

```bash
java -version
docker info
```

Use the included Maven wrapper. Tests start their own application instances and disposable databases; leave the development database out of the test setup.

Allow network access for uncached downloads and dependency vulnerability scans. Security scans require Bash.

On Windows, replace `./mvnw` with `mvnw.cmd`.

<a id="how-to-run"></a>

## Commands

### Full verification

Runs formatting checks, unit tests, integration tests and static analysis:

```bash
./mvnw -B -ntp clean verify
```

Security scans and performance testing run separately. `-B -ntp` keeps output suitable for automated runs.

> `clean` deletes generated reports. Save required evidence elsewhere first. Omitting `clean` avoids that deletion, but checks can still overwrite their own reports.

### Unit tests

For quick feedback on individual logic; includes formatting checks:

```bash
./mvnw test
```

### Selected integration tests

Choose the relevant group:

```bash
# Creation and saved data
./mvnw -Dit.test=CreateLinkIT,LinkCreationIT,CreationLifecycleIT,DestinationMigrationIT verify

# Redirects
./mvnw -Dit.test=RedirectLinkIT,CreationLifecycleIT verify

# Usage statistics
./mvnw -Dit.test=LinkAnalyticsIT,AnalyticsFailureIT,AnalyticsPersistenceIT verify
```

These retain unit tests, formatting and static analysis. Run full verification before considering the change verified.

### Security scans

Checks dependencies, current files and locally available Git history:

```bash
bash scripts/security-checks.sh
```

### Formatting

Applies Java formatting changes:

```bash
./mvnw spotless:apply
```

Review the changes and run verification again.

For the performance command and instructions, see [Measurements and results](measurements-and-results.md#redirect-performance-baseline).

<a id="automated-security-and-ci"></a>

## Automated checks

[GitHub Actions](../.github/workflows/verify.yml) runs full verification and security scans as separate jobs on pull requests, pushes to `main`, manual runs and a weekly schedule. Performance testing runs separately.

Available reports are uploaded even when a job fails and retained for seven days.

## Limits

- Passing tests cover the implemented cases, not every possible behavior.
- No code-coverage percentage threshold is configured.
- Dependency scans exclude Maven build plugins, the JDK and container images.
- Secret scans cover current files and locally available history; generated `target/` files are excluded from the current-file scan.
- Successful security scans do not establish complete security. New vulnerability reports can change later results.

Performance limits and qualifications for individual runs belong in [Measurements and results](measurements-and-results.md).
