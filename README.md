# URL shortener

Create reusable short links, follow them to their saved destinations and read their recorded usage. The service runs locally using Java, Spring Boot and PostgreSQL.

## Prerequisites

- JDK 21.
- Git and curl.
- Docker with Compose and a running Docker daemon.
- Network access for dependency downloads and vulnerability scans.

Check the main prerequisites:

```sh
java -version
javac -version
docker info
docker compose version
```

The included Maven wrapper supplies Maven; no separate Maven installation is required.

The walkthrough was verified on macOS. Commands use POSIX shell syntax. On Windows, use `mvnw.cmd` (`.\mvnw.cmd` in PowerShell) and `curl.exe`, adjusting quoting and line continuations for your shell.

## Get the project

```sh
git clone https://github.com/jyshnkr/url-shortener.git
cd url-shortener
```

Run the following commands from this directory.

## Verify the project

```sh
./mvnw -B -ntp clean verify
```

This runs formatting checks, unit tests, integration tests and static analysis. Expect `BUILD SUCCESS`.

Tests start their own disposable databases. Docker must be running, but the development database does not need to be started. The first run downloads Maven and the required dependencies.

See [Testing](docs/testing.md) for focused commands, report locations and failure guidance.

## Start the service

Make sure ports **5432** and **8080** are available.

Start PostgreSQL:

```sh
docker compose up -d --wait
```

Then start the application:

```sh
./mvnw spring-boot:run
```

Database migrations run automatically. Leave this terminal running.

In a second terminal, check the service:

```sh
curl --fail http://localhost:8080/actuator/health
```

Expect JSON containing `"status":"UP"`.

The default configuration binds the application and database to the local machine and uses local demonstration credentials.

**Database isolation:** Compose uses the fixed project name `url-shortener-restart`. Another checkout can therefore reuse the same database. For a separate database, use a distinct project name, such as `docker compose -p url-shortener-demo up -d --wait`, and use that same `-p` option for every subsequent Compose command. The default ports still need to be available.

## Try the service

### Create a short link

```sh
curl -i http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"destinationUrl":"https://example.com"}'
```

Expect `201 Created` with `code`, `shortUrl` and `destinationUrl` in the response body. The `Location` header contains the short URL.

If that exact destination is already saved, expect `200 OK` with its existing link.

### Reuse the link

Run the same creation command again.

Expect `200 OK` with the same code and short URL. Creation validates the address without contacting the destination website.

Copy the returned `code` and replace `RETURNED_CODE` in the following commands.

### Inspect and follow the redirect

Inspect the shortener’s response:

```sh
curl -i http://localhost:8080/r/RETURNED_CODE
```

Expect `302 Found`, the destination in `Location`, `Cache-Control: no-store` and an empty body.

To let curl follow the redirect:

```sh
curl -L http://localhost:8080/r/RETURNED_CODE
```

This makes a separate request to the destination website. You can also open the returned `shortUrl` in a browser.

### Read recorded usage

```sh
curl -i http://localhost:8080/api/v1/links/RETURNED_CODE/stats
```

Expect `200 OK` with:

- `code`: the short code.
- `redirectCount`: the number of recorded redirect requests.
- `lastRedirectedAt`: the latest recorded request time in UTC.

Recording happens in the background, so counts may take a short time to appear. Before any redirect is recorded, the count is zero and the timestamp is null. Creating or reusing a link and reading its statistics do not increase the count.

See [Architecture](docs/architecture.md) for address rules, request flows and recording limits.

## Stop the service

Press **Ctrl-C** in the application terminal, then run:

```sh
docker compose down
```

This stops the database and retains saved links and usage in its volume. Starting the service again reuses that data.

Adding `--volumes` deletes the database volume and its saved data.

## Additional checks

Run dependency and secret scans:

```sh
bash scripts/security-checks.sh
```

This requires Bash, Docker and network access for the dependency scan. See [Testing](docs/testing.md) for report locations and scan coverage.

Run the separate performance check:

```sh
./mvnw -B -ntp -Pperformance verify
```

This replaces the ordinary integration suite with the performance test, so run it after full verification. See [Measurements and results](docs/measurements-and-results.md) for the workload, reports and interpretation.

## Troubleshooting

| Problem | What to check |
|---|---|
| Docker commands or database tests cannot connect | Start Docker and confirm that `docker info` succeeds. |
| PostgreSQL or the application cannot bind its port | Check whether another process uses port 5432 or 8080. Stop the conflicting instance if appropriate, then retry. |
| A fresh checkout returns an existing link | Check the Compose project name. Existing database volumes survive shutdown and can be shared across checkouts. |
| Usage has not appeared yet | Wait briefly and read statistics again. Recording is asynchronous; persistent gaps can indicate dropped or unconfirmed events. See the application logs and Architecture. |

## Documentation guide

| If you want to… | Read |
|---|---|
| Understand the approach, outcomes and trade-offs | [Engineering summary](docs/engineering-summary.md) |
| Understand the design, requirements and request flows | [Architecture](docs/architecture.md) |
| Run checks or investigate failures | [Testing](docs/testing.md) |
| Run performance checks and interpret results | [Measurements and results](docs/measurements-and-results.md) |
| See how new capabilities were built | [Greenfield scenarios](docs/scenarios/01-greenfield.md) |
| See how existing behavior was changed safely | [Brownfield scenarios](docs/scenarios/02-brownfield.md) |
| See how unclear requirements became agreed rules | [Ambiguous-requirement scenarios](docs/scenarios/03-ambiguous.md) |
| Understand human decisions and AI contributions | [AI-human traceability log](docs/ai-log.md) |
| Look up project terminology | [Domain language](CONTEXT.md) |
