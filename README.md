# URL shortener

A Java/Spring Boot URL shortener, built collaboratively in small reviewed increments.

**Phase 1 accepted; Phase 2A creation implemented, awaiting human review.** URL-only creation, destination reuse and database constraints are tested locally. Redirect, expiration, analytics and optional API-key protection are not implemented. See [the current increment](docs/scenarios/01-greenfield.md) and [engineering summary](docs/engineering-summary.md).

## Prerequisites

- JDK 21 (`java -version`).
- Docker with a running daemon and Compose v2 or later (`docker info`, `docker compose version`). Docker Desktop works on macOS/Windows.
- Internet access for the first Maven dependency and container-image downloads.
- Ports 8080 and 5432 free for local startup. Tests use random ports.

Maven 3.9.16 is supplied through the wrapper; no global Maven installation is required. Spring Boot is pinned to 4.1.1, with the approved Tomcat 11.0.26 patch override. Compose and tests both use PostgreSQL 17.11. Other dependency versions follow the Boot parent.

## Run locally

From the project root:

```sh
docker compose up -d --wait
./mvnw spring-boot:run
```

In another terminal:

```sh
curl --fail http://127.0.0.1:8080/actuator/health
```

The health response should contain `"status":"UP"`; Spring may also list health groups. Flyway applies schema migrations automatically during startup. V2 preserves existing mappings, but aborts if duplicate destinations or fingerprint conflicts exist; it never merges or deletes them. See [migration behavior](docs/architecture.md#persistence-and-migration).

Create a link:

```sh
curl -i http://127.0.0.1:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"destinationUrl":"https://example.com/docs"}'
```

- Expect `201`, a `Location` header, and JSON containing `code`, `shortUrl`, `destinationUrl`.
- Repeat the exact destination to receive `200` with the original result and `Location`. Different destination strings create independent mappings.
- Send no `Idempotency-Key` header: even an empty value returns `400` with instructions to remove it.
- The short URL defaults to `http://localhost:8080/r/{code}`. Set `SHORTENER_BASE_URL` before startup to change the trusted base; request headers cannot choose it. Previously saved results keep their original base.
- **Following the short URL is not implemented yet.** Input and error rules: [architecture](docs/architecture.md#creation-contract).

The application and PostgreSQL bind to loopback by default. The database username `url_shortener` and password `local-development-only` are public **local demo credentials**, not production secrets. Do not expose this configuration publicly. For a different environment, Spring accepts `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`; configure the database consistently too.

Stop the application with Ctrl-C, then:

```sh
docker compose down
```

This retains the development database in `url-shortener-restart_postgres_data`. Do not add `--volumes` unless you intentionally want to erase that database. The separately named archive volume is not used.

## Verify

With Docker running (Compose does not need to be running):

```sh
./mvnw verify
```

Tests provision their own PostgreSQL; they do not use the development database. `./mvnw test` does **not** run integration tests; use `verify`. See [testing](docs/testing.md) for test names, commands, observed results and remaining quality gates.

On Windows, use `mvnw.cmd` in place of `./mvnw` and `curl.exe` if your shell aliases `curl`.

## Layout

- `src/main/java`: Spring Boot entry point and the `links` creation module.
- `src/main/resources/db/migration`: Flyway schema migrations.
- `src/test/java`: validation unit tests and isolated PostgreSQL/API integration tests.
- `docs/`: the documentation below; each file owns one topic.
- `AGENTS.md`: collaboration boundaries and agent instructions.

## Documentation

| Document | Purpose |
| --- | --- |
| [Architecture](docs/architecture.md) | Application diagram, request flow, choices and risks |
| [Testing](docs/testing.md) | Tools, commands, results and remaining checks |
| [AI-human log](docs/ai-log.md) | Meaningful proposals, corrections and human decisions |
| [Engineering summary](docs/engineering-summary.md) | Objective, progress, evidence, risks and next step |
| [Greenfield](docs/scenarios/01-greenfield.md) | New-service scope, task sequence, execution and validation |
| [Brownfield](docs/scenarios/02-brownfield.md) | A later change to a working baseline, with impact and regression evidence |
| [Ambiguous requirement](docs/scenarios/03-ambiguous.md) | Clarifying availability versus analytics completeness |
| [Domain language](CONTEXT.md) | Shared meaning of short link, destination reuse and analytics terms |
