# Hot Room QPS Threshold Results

Test date: 2026-10-04

## Environment

- Backend: Spring Boot, Java 21, Windows host
- Database: MySQL benchmark database `auction_bench`
- Hot-path Redis: backend, MySQL, and Redis run in the same Docker network
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

## Threshold Transition Test

The final threshold test ran the backend, MySQL, and Redis in the same Docker
network. This removes Windows host-to-container port NAT from the application
request path.

All requests in this test were valid bids. Each stage used a new room and a new
funded bidder.

| Target QPS | Achieved QPS | Promoted | Promotion Time | Pre-HOT P95 | Post-HOT P95 |
|---:|---:|---|---:|---:|---:|
| 20 | 20.01 | No | - | 61.78 ms | - |
| 25 | 25.04 | Yes | 5.69 s | 42.18 ms | 25.16 ms |
| 30 | 30.01 | Yes | 4.22 s | 41.56 ms | 20.65 ms |
| 40 | 39.99 | Yes | 3.81 s | 34.68 ms | 19.42 ms |

This verifies the intended behavior:

- 20 bid/s remains on MySQL and does not enter HOT.
- 25 bid/s enters HOT after the rolling five-second window reaches the
  threshold.
- Once HOT, P95 improves because requests no longer contend for the MySQL room
  row lock.
- 30 and 40 bid/s reach the rolling threshold earlier and continue to show
  lower post-promotion latency.

## End-To-End Hot Capacity

This test runs multiple independent HOT rooms at the same time. Each room has
one worker that sends valid bids sequentially. The backend, MySQL, and Redis
run in the same Docker network.

| HOT Rooms | Total QPS | QPS Per Room | P50 ms | P95 ms | P99 ms | HTTP 200 |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 42.19 | 42.19 | 21.59 | 34.05 | 57.62 | 633 |
| 5 | 211.97 | 42.39 | 21.75 | 34.70 | 46.18 | 3,183 |
| 10 | 331.51 | 33.15 | 28.11 | 41.51 | 53.89 | 4,978 |
| 20 | 413.42 | 20.67 | 43.86 | 65.10 | 109.80 | 6,212 |
| 40 | 366.72 | 9.17 | 95.76 | 138.25 | 643.02 | 5,514 |

Interpretation:

- One room can process about 42 valid bids per second in this environment.
- The application remains stable through 20 concurrent HOT rooms and about
  413 total QPS.
- At 40 HOT rooms, total QPS decreases, per-room QPS falls sharply, and P99
  rises to 643 ms. This is the application-side knee.
- For this single-node deployment, keep the effective HOT-room count below
  roughly 20 and scale application instances or Redis shards before increasing
  this limit.

## Raw Redis Benchmark

Measured inside the Redis master container with `redis-benchmark` and 100
concurrent clients:

| Command | Requests Per Second | P50 |
|---|---:|---:|
| PING_INLINE | 27,225.70 | 1.80 ms |
| SET | 19,573.30 | 2.80 ms |
| GET | 26,946.91 | 1.82 ms |

This is a Redis-only upper-bound reference. The application and full Lua flow
are still far below these numbers because HTTP, Spring, wallet validation,
leaderboard updates, event publishing, and Stream persistence add work.

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
44.45 * 0.55 ~= 24.5, rounded to 25 bid/s
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
