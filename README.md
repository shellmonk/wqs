# WQS: Wallet Query Service

The read side of the wallet's CQRS split. It projects wallet data into its own Postgres database
and serves it to `/web` (players) and `/cc` (customer care).

- **Immutable transactions** arrive from the wallet's `walAccountTransactions` table over a
  RabbitMQ stream.
- **Mutable sources** (claimable prizes, payments, manual adjustments, limits) are polled from
  the wallet's `/wallet/delta` endpoint by a scheduled job.

The wallet's inputs are documented in `docs/reference/`.

## Stack

Java 21 · Spring Boot 3.4 (MVC, virtual threads) · Spring Data JPA (native queries for atomic writes) ·
Flyway · Postgres 16 · Quartz (clustered JDBC store) · RabbitMQ Streams · MapStruct · Lombok ·
Testcontainers. One Gradle build file (Groovy DSL).

## Run locally

```bash
colima start                                           # or any Docker engine
./gradlew bootJar
docker compose --profile app up -d --build             # Postgres, RabbitMQ (streams) and the app on :8080
./gradlew publishSamples -Pcount=200 -Powner=1-1717    # push sample wallet messages onto the stream
```

Then:

```bash
FROM=$(date -u -v-31d +%Y-%m-%dT%H:%M:%SZ); TO=$(date -u -v+1d +%Y-%m-%dT%H:%M:%SZ)
curl "localhost:8080/cc/wallet-data/rest/v1/projection/1-1717?from=$FROM&to=$TO&aggregationLevel=1"
curl -H 'X-Owner-Id: 1-1717' "localhost:8080/web/wallet-data/rest/v1/projection?from=$FROM&to=$TO"
```

- API docs: http://localhost:8080/swagger-ui.html (OpenAPI at `/v3/api-docs`).
- RabbitMQ UI: http://localhost:15672 (guest/guest).
- `docker compose up -d` without the profile starts only Postgres (host port 55432) and RabbitMQ,
  for running the app from the IDE or with `./gradlew bootRun`.

**No authentication yet.** Both endpoints are closed by default: `/cc` answers 403 unless
`wqs.api.cc-enabled=true`, `/web` answers 401 unless `wqs.api.web-owner-header=true` (it then
trusts an `X-Owner-Id` header that any caller can set). The compose app turns both on: local use only.

The stream consumer is off by default. `WQS_STREAM_ENABLED=true` may be set on every replica: a
Postgres advisory lock lets one consume and the rest stand by (see `docs/operations.md`). When the broker advertises an address the app cannot reach (a load
balancer, another container), set `WQS_STREAM_CONNECT_HOST`.

## Build and test

```bash
./gradlew clean build
./gradlew benchmark        # opt-in: read and ingest benchmarks, results in build/benchmark/
```

Tests run against real Postgres and RabbitMQ containers through Testcontainers. On Colima, point
Testcontainers at its socket in `~/.testcontainers.properties`:

```
docker.host=unix:///Users/<you>/.colima/default/docker.sock
```

The test broker binds a free host port picked per run, so it never clashes with a `docker compose` broker on 5552.

## Conventions

- Database identifiers are camelCase and always double-quoted in SQL (`"ownerId"`).
- An owner is identified by the wallet's `ownerId`, `"{type}-{id}"` (e.g. `1-1717`), everywhere: in
  storage, in `/cc/.../projection/{ownerId}` and in the `/web` identity. The type has no meaning of
  its own; `wqs.util.OwnerIds` gives each owner one canonical spelling.
- Every value is bound as a parameter; nothing is concatenated into SQL.
- Spring layering: `config`, `dto`, `entity`, `exception`, `mapper`, `repository` (one Spring Data
  JPA repository per table), `service`, `stream`, `util`.
- Transactions are declared with `@Transactional` on service methods only. A
  `@Transactional(readOnly = true)` method runs on the reader pool, anything else on the writer; a
  read request uses one read-only REPEATABLE READ transaction, so it sees one snapshot.
- Writes that must be atomic (insert-unless-present, session and play upserts) are native
  `@Query` methods on the repositories; Hibernate validates the entities against the schema at startup.
- One schema migration, `V1__baseline.sql`, edited in place until go-live.
