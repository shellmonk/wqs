# WQS: Wallet Query Service

The read side of the wallet's CQRS split. It projects wallet data into its own Postgres database
and serves it to `/web` (players) and `/cc` (customer care).

- **Immutable transactions** arrive from the wallet's `walAccountTransactions` table over a
  RabbitMQ stream.
- **Mutable sources** (claimable prizes, payments, manual adjustments, limits) are polled from
  the wallet's `/wallet/delta` endpoint by a scheduled job.

The wallet's inputs are documented in `docs/reference/`.

## Stack

Java 21 · Spring Boot 3.4 (MVC, virtual threads) · Spring `JdbcClient` with hand-written SQL ·
Flyway · Postgres 16 · Quartz (clustered JDBC store) · RabbitMQ Streams · MapStruct · Lombok ·
Testcontainers. One Gradle build file (Groovy DSL).

## Run locally

```bash
colima start                      # or any Docker engine
docker compose up -d              # Postgres on 55432, RabbitMQ streams on 5552, UI on 15672
./gradlew bootRun                 # http://localhost:8080/actuator/health
```

The stream consumer is off by default. Enable it on exactly one replica with
`WQS_STREAM_ENABLED=true`.

## Build and test

```bash
./gradlew clean build
```

Tests run against real Postgres and RabbitMQ containers through Testcontainers. On Colima, point
Testcontainers at its socket in `~/.testcontainers.properties`:

```
docker.host=unix:///Users/<you>/.colima/default/docker.sock
```

The test broker binds host port 15552, so it does not clash with a `docker compose` broker on 5552.

## Conventions

- Database identifiers are camelCase and always double-quoted in SQL (`"userId"`).
- Every value is bound as a parameter; nothing is concatenated into SQL.
- Writes use the primary (writer) pool. API reads use `@ReaderJdbc` inside one `@ReaderTx`
  transaction (read-only, REPEATABLE READ), so a request sees one snapshot.
- One schema migration, `V1__baseline.sql`, edited in place until go-live.
