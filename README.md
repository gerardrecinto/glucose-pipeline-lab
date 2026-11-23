# glucose-pipeline-lab

A small Java service for glucose sensor data: ingest readings, reject and count bad rows, store them idempotently, compute the standard CGM report metrics, and serve them over a REST API with an insight layer that can only say what the numbers show.

Docs and design decisions: https://gerardrecinto.github.io/glucose-pipeline-lab/

All data is synthetic. This is a portfolio project, not a medical device, and it makes no FDA, ISO or HIPAA claim.

## Run it

Needs JDK 17+ and Maven. About 2 minutes.

```bash
git clone https://github.com/gerardrecinto/glucose-pipeline-lab.git && cd glucose-pipeline-lab
mvn -B verify                     # 13 tests, builds target/glucose-pipeline.jar
java -jar target/glucose-pipeline.jar --demo
curl -s localhost:8080/v1/subjects/subject-002/summary
```

`--demo` loads three synthetic subjects: `subject-001` steady, `subject-002` high variability with lows, `subject-003` a sensor that was mostly off. Port in use? Add `--port 8081`.

## API

| Route | What it does |
|---|---|
| `POST /v1/readings` | Batch of `{deviceId, subjectId, ts, mgdl}`. Up to 5,000 rows. Replies with accepted, duplicates and rejected rows with a reason. Retrying a batch is safe. |
| `GET /v1/subjects/{id}/summary?days=14` | Time in range, below, very low, above, very high, mean, SD, CV, GMI, coverage and gaps, plus insights. |
| `GET /v1/subjects/{id}/readings?from=&to=` | Raw readings, epoch seconds. |
| `GET /v1/quality` | Accepted, duplicate and rejected counts by reason. |
| `GET /healthz`, `GET /metrics` | Liveness and Prometheus counters. |

Set `API_TOKEN` to require `Authorization: Bearer <token>` on `/v1/*`.

## How it works

| Piece | Behavior |
|---|---|
| `Validator` | Rejects out-of-range (20 to 600 mg/dL), future, missing or zero timestamps with a reason code. |
| `ReadingStore` | Append-only JSONL log plus an in-memory index per subject. Idempotent on (subject, timestamp), accepts out-of-order rows, replays the log on start, and can purge by age. |
| `Stats` | Consensus CGM metrics. A window with under 70% sensor coverage is marked not sufficient. |
| `RuleInsights` and `InsightGuard` | Insights name the target a metric misses and cite the summary field. The guard drops any insight, from rules or a future model, whose evidence does not match the summary or whose text has dosing or treatment words. |

## Choices

- **JSONL log and in-memory index, not a database.** No setup and easy to replay and test. It costs memory and allows one writer. Move to Postgres or a time-series store when data outgrows RAM or a second instance is needed.
- **First write wins on (subject, timestamp).** It makes retries safe. A corrected value for an existing timestamp is ignored. Switch to versioned upserts if devices send corrections.
- **Reject at the door and count by reason.** Bad rows never reach the metrics. Rejected rows are returned to the sender and counted, not stored.
- **Summary window ends at the subject's latest reading.** Results are reproducible. A subject whose sensor stopped looks current, so a real system also needs a last-seen check.

## Not covered

No TLS, no per-user auth beyond one shared token, no multi-instance deployment, no real device protocol, no regulated-software process. Metrics use the published consensus targets; I did not validate them against clinical data.
