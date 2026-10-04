# Hot Room QPS Threshold Results

Test date: 2026-10-04

## Environment

- Backend: Spring Boot, Java 21, Windows host
- Database: MySQL benchmark database `auction_bench`
- Hot-path Redis: local Docker Sentinel topology, one master and two replicas
- Load generator: Node.js HTTP benchmark, no JMeter dependency
- Rate limit: disabled only in the benchmark process environment

## Metric Definition

The hot-room upgrade threshold uses **single-room valid bid QPS**, not total
site QPS and not room-detail view QPS.

## Cold-Path Valid Bid Test

Test properties:

- One room
- One bidder account with a dedicated starting balance
- Requests are sent sequentially and every bid amount increases by `1.00`
- Duration: 30 seconds

| Metric | Result |
|---|---:|
| Requests | 1,334 |
| HTTP 200 | 1,334 |
| Achieved QPS | 44.45 bid/s |
| Average | 22.49 ms |
| P50 | 21.96 ms |
| P95 | 27.58 ms |
| P99 | 37.15 ms |
| Max | 75.35 ms |

This is a closed-loop measurement. It is a conservative lower bound for the
cold-path service rate because only one request is in flight at a time.

## Cold-Path Contention Sweep

This test keeps the same bid amount after the first successful bid. Most
requests are expected to fail with `400`, but they still exercise MySQL room
row locking.

| Offered QPS | Achieved QPS | P95 ms | P99 ms | Max In Flight |
|---:|---:|---:|---:|---:|
| 10 | 10.07 | 30.15 | 56.42 | 1 |
| 20 | 20.02 | 24.11 | 30.66 | 2 |
| 30 | 30.04 | 21.32 | 34.94 | 2 |
| 50 | 49.98 | 19.85 | 31.16 | 4 |
| 80 | 79.91 | 19.33 | 24.26 | 5 |
| 100 | 99.90 | 18.56 | 35.92 | 10 |
| 150 | 149.87 | 20.69 | 45.63 | 17 |
| 200 | 199.83 | 24.78 | 45.58 | 14 |
| 300 | 299.38 | 97.83 | 118.84 | 43 |
| 500 | 302.20 | 9,573.54 | 9,862.89 | 3,063 |

The clear knee is between 200 and 300 offered QPS. The 500 QPS stage collapses
into queue buildup and is not a useful operating point.

## Local Hot-Path Result

The local Windows-to-Docker Redis path measured only 14.6 to 17.7 valid bid/s
after initial optimization. The main cause is Docker Desktop network overhead
and multiple Redis round trips per bid, not Redis Lua execution itself.

This result is recorded for completeness but is not used to choose the
threshold. Production benchmarking must run the application and Redis on the
same LAN or in the same cluster network.

## Threshold Selection

Cold-path valid service rate:

```text
44.45 bid/s
```

Safety utilization:

```text
55% to 60%
```

Calculated threshold:

```text
44.45 * 0.55 = 24.45
44.45 * 0.60 = 26.67
```

Selected production threshold:

```text
Enter HOT: 25 bid/s for 5 seconds
Exit HOT: 6 bid/s for 60 seconds
```

Room-detail access QPS is not a write-engine promotion trigger. It may be used
later for cache warmup only.

## What The Data Means

### Offered QPS

The client-side target request rate. For example, `300` means the load
generator tried to start 300 requests per second.

### Achieved QPS

The actual number of completed requests divided by elapsed time. If achieved
QPS is much lower than offered QPS, requests are queuing and the service has
entered overload.

### Average, P50, P95, P99

These are HTTP response-time percentiles:

- Average shows the general level.
- P50 means 50% of requests completed within this time.
- P95 means 95% completed within this time and the slowest 5% were slower.
- P99 highlights tail latency and is more important than average for auction
  correctness and user experience.

### HTTP 200 And HTTP 400

In the valid test, all `1,334` requests returned `200`, so the service rate is
based on fully valid bids.

In the contention sweep, only the first request can win with the fixed bid
amount. Later requests return `400 BID_TOO_LOW`, but they still exercise the
same MySQL room row lock. This test is useful for finding lock-contention and
queue-collapse points, not for measuring maximum valid throughput.

### Max In Flight

The maximum number of HTTP requests simultaneously waiting for a response.
If this grows rapidly while achieved QPS stops increasing, the system is
overloaded.

### 44.45 bid/s

This is the measured sequential valid-bid service rate for one room:

```text
1 second / 22.49 ms average = about 44.5 completed bids per second
```

The actual value is limited by the measured `44.45 bid/s`.

### 25 bid/s

The target upgrade threshold:

```text
44.45 * 0.55 ≈ 24.5, rounded to 25 bid/s
```

This keeps the cold path below roughly 56% utilization before switching the
room to Redis.

### 500 QPS Collapse

At the 500 QPS contention stage:

- Offered QPS was 500.
- Achieved QPS was only 302.
- P95 increased to 9.57 seconds.
- Max in-flight reached 3,063.

This means the room has already entered an unbounded queue, so it is far beyond
a safe promotion point.

## Reproduce

Initialize the isolated MySQL database:

```powershell
cmd.exe /c "docker run --rm -i mysql:8.4 mysql -h host.docker.internal -uroot -p123456 --default-character-set=utf8mb4 < D:\auction\backend\mysql-benchmark.sql"
```

Run a cold-path valid bid test:

```powershell
$env:BENCHMARK_MODE = "valid"
$env:BENCHMARK_DURATION_SECONDS = "30"
$env:BENCHMARK_STAGES = "1"
node D:\auction\jmeter\bid-qps-benchmark.mjs
```

Run the contention sweep:

```powershell
$env:BENCHMARK_MODE = "contention"
$env:BENCHMARK_DURATION_SECONDS = "15"
$env:BENCHMARK_STAGES = "10,20,30,50,80,100,150,200,300"
node D:\auction\jmeter\bid-qps-benchmark.mjs
```
