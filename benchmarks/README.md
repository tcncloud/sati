# WorkStream v3 Benchmark Harness

End-to-end performance harness for the sati Java client talking to a
benchmark-only reimplementation of the exile gatev3 WorkerService. Used to
establish a baseline before the WorkStream v3 throughput work (epic:
`exile/-/epics/9`) and measure each improvement as it lands.

## What's here

- `benchmarks/` (this subproject): Java harness that drives load through the
  real `ExileClient` and records latency/throughput.
- `../../tcn/exile/exile/gatev3-benchmark-server/`: Go server that faithfully
  reproduces the current gatev3 WorkerService behaviour (hardcoded 32-entity
  event batches, 500 ms idle backoff, concurrent `stream.Send`, synchronous
  result persist when `--db-latency` is set). No Postgres, no Redis.

## Prerequisites

- JDK 21+
- Go 1.24+
- A local clone of `git.tcncloud.net/exile/exile` alongside sati:
  ```
  $HOME/dev/tcncloud/sati          <- this repo
  $HOME/dev/tcn/exile/exile        <- or elsewhere: set EXILE_REPO
  ```

## Run it

```bash
make benchmark                               # all scenarios, 60 s each
make benchmark SCENARIO=mixed-steady         # one scenario
make benchmark DURATION=30                   # shorter run
make benchmark MAX_CONCURRENCY=50            # what happens at higher concurrency
make benchmark JOB_LATENCY_MS=200            # slower plugin
```

Reports are written to `benchmarks/results/<timestamp>.json` and also printed
to stdout.

## Scenarios

| Name                     | What it drives | What we're measuring |
|--------------------------|----------------|----------------------|
| `burst-events-10k`       | 1000 events/s pushed by the server generator | event throughput ceiling (current: ~64/s due to 500 ms backoff + batch=32) |
| `sustained-jobs-100rps`  | 100 job/s injected via `/inject-job` | job-only RTT + concurrency cap |
| `mixed-steady`           | 100 jobs/s + 500 events/s simultaneously | priority fairness — jobs vs events |
| `events-ramp-200rps`     | 200 events/s sustained | pair with `EVENT_LATENCY_MS=200` to stress the concurrency cap (current default `maxConcurrency=5` → ~25 items/s with a 200 ms plugin) |

## Report shape

```json
{
  "metadata": {
    "timestamp": "2026-04-15T...",
    "grpc_host": "localhost:50051",
    "max_concurrency": 5,
    "duration_s": 60,
    "job_latency_ms": 10,
    ...
  },
  "scenarios": {
    "mixed-steady": {
      "duration_s": 60.0,
      "throughput": { "jobs_per_s": 100.0, "events_per_s": 60.0 },
      "total":      { "jobs": 6000, "events": 3600 },
      "errors":     { "jobs": 0, "events": 0 },
      "job_latency_ms":   { "p50": 12, "p95": 540, "p99": 820, "max": 1024 },
      "event_latency_ms": { "p50": 7, "p95": 240, "p99": 480, "max": 790 },
      "server_stats": { "jobs_dispatched": 6000, ... }
    }
  }
}
```

Latency is **client-side processing time** (from plugin handler entry to exit,
recorded in `BenchmarkPlugin`). This closely approximates end-to-end latency
over localhost.

## Comparing runs

Two JSON reports can be diffed by hand or with `jq`:

```bash
jq '.scenarios."mixed-steady".throughput' baseline.json
jq '.scenarios."mixed-steady".throughput' after-c1.json
```

## Current state (as of 2026-04-15)

Baseline numbers will be recorded here once the first run completes — see
`benchmarks/results/baseline-<date>.json`.

## Faithfulness caveat

The Go benchmark server is a **simulator**, not the real gatev3 package —
`*exiledb.Repository` is a concrete struct (not an interface), so we can't
run the real gatev3 GrpcApi without Postgres. The simulator reproduces the
observable behaviour of each bottleneck documented in the epic:

- `eventBatchSize = 32` (hardcoded, ignores `pull.MaxItems`)
- 500 ms idle backoff in the event poller
- Concurrent `stream.Send` from heartbeat / lease / recv goroutines
- Synchronous "DB write" on result (via `--db-latency`)

When the server-side fixes (S1, S2, S3) land, update the simulator to match
and re-run to confirm the gains show up.
