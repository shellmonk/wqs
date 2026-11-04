# Operating WQS

## Replicas and the stream

Any number of replicas may run with `WQS_STREAM_ENABLED=true`. They compete for a Postgres
advisory lock named after the stream (`wqs-stream:<name>`); the holder consumes, the others stand
by and retry every 5 seconds. The lock lives on one held writer connection: if the leader's process
or connection dies, Postgres releases it and a standby takes over within seconds, from the offset
stored in `"wqIngestCursors"`. Writes are idempotent, so the short overlap possible during a
handover cannot double-count.

Which replica leads: `wqs_ingest_stream_leader == 1`, or `state: CONSUMING` in `/actuator/health`.

## Scheduled jobs (Quartz, clustered: one replica runs each job)

| Job | When | What |
|---|---|---|
| `partitionMaintenance` | at startup, then hourly | creates the current month's partition of `"wqPrjTransactions"` and the next `wqs.partition.create-ahead-months` (3) |

## Health

`/actuator/health` lists its components (`db`, `streamConsumer`, ...). `streamConsumer` is DOWN
only when this replica leads and is stuck: messages waiting, nothing committed for 5 minutes. It
is not part of the liveness or readiness probes (`/actuator/health/liveness`, `/readiness` on
Kubernetes), so a broker problem never restarts the service or stops it serving reads.

## Metrics (Prometheus at `/actuator/prometheus`)

| Metric | Meaning |
|---|---|
| `wqs_ingest_stream_leader` | 1 on the consuming replica, 0 on standbys |
| `wqs_ingest_stream_lag` | messages in the stream not yet committed (leader only; a lower bound, short by at most one broker chunk) |
| `wqs_ingest_stream_last_commit_age_seconds` | seconds since the last committed batch (leader only) |
| `wqs_ingest_stream_messages_total{outcome}` | messages by outcome: `stored`, `already_stored`, `dead_lettered` |
| `wqs_ingest_stream_batch_size` | messages per committed batch (summary) |
| `wqs_ingest_stream_lost_messages_total` | messages removed by stream retention before they were read |
| `http_server_requests_seconds{uri}` | API latency, from Spring Boot |
| `hikaricp_connections_*{pool}` | `writer` and `reader` pool usage |

## Suggested alerts

```yaml
- alert: WqsNoStreamLeader            # nobody consumes
  expr: max(wqs_ingest_stream_leader) < 1
  for: 2m
- alert: WqsStreamLagging
  expr: max(wqs_ingest_stream_lag) > 10000
  for: 10m
- alert: WqsStreamStalled             # messages waiting, nothing committed
  expr: max(wqs_ingest_stream_lag) > 0 and max(wqs_ingest_stream_last_commit_age_seconds) > 300
  for: 2m
- alert: WqsMessagesLostToRetention   # data is missing from the projection: needs a backfill
  expr: increase(wqs_ingest_stream_lost_messages_total[1h]) > 0
- alert: WqsDeadLetters
  expr: increase(wqs_ingest_stream_messages_total{outcome="dead_lettered"}[15m]) > 0
- alert: WqsSlowReads
  expr: histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri=~"/(web|cc)/.*"}[5m]))) > 1
  for: 10m
```

(`http_server_requests_seconds_bucket` needs
`management.metrics.distribution.percentiles-histogram.http.server.requests=true`.)

## When something is wrong

- **Dead letters:** `select "reason", count(*) from "wqDeadLetters" group by 1`. An unreadable
  message is identified by `"streamOffset"` and `"bodySha256"`; the message itself is still in the
  stream at that offset. A rejected one keeps its allowlisted fields in `"payload"`.
- **Lost messages:** the log line names the offset range. Those rows must come from the backfill.
- **Stalled leader:** restart that replica; a standby takes the lock when its connection closes.
