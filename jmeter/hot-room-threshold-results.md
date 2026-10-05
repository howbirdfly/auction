# Hot Room QPS Threshold Results

Test date: 2026-10-04

## 结果总览

以下数据必须在同一口径下阅读。单房间 QPS、端到端总 QPS 和 Redis
原生 QPS 回答的是不同问题，不能直接互相替代。

| 指标 | 实测结果 | 测试条件与说明 |
|---|---:|---|
| 最高稳定端到端吞吐 | **413.42 QPS** | 20 个 HOT 房间并发；全部 HTTP 200；P95 65.10 ms，P99 109.80 ms |
| 单房间单连接热路径 | **42.19 bid/s** | 1 个 HOT 房间、每房间 1 个顺序 worker；这是延迟上限，不是单房间容量上限 |
| 单房间热路径并发稳定区 | **200 QPS** | 固定金额竞争、绝大多数请求按预期返回 `400`；P95 15.19 ms，P99 21.11 ms |
| 单房间热路径容量拐点 | **300 QPS** | 可以完成 298.62 QPS，但 P95 已升至 385.73 ms，队列开始增长 |
| 单房间冷路径有效出价 | **44.45 bid/s** | 1 个房间、1 个账号、顺序递增出价；平均 22.49 ms，P95 27.58 ms |
| 进入 HOT 阈值 | **25 bid/s** | 单房间 5 秒滑动窗口；按冷路径约 56% 安全利用率确定 |
| 退出 HOT 阈值 | **6 bid/s** | 持续 60 秒低于阈值后退出 |
| Redis 原生 PING 上限 | **27,225.70 req/s** | Redis 单容器、100 并发；仅代表 Redis 资源上限 |
| Redis 原生 SET 上限 | **19,573.30 req/s** | Redis 单容器、100 并发；不包含 Spring、Lua 和完整业务链路 |
| Redis 原生 GET 上限 | **26,946.91 req/s** | Redis 单容器、100 并发；不包含 HTTP 和业务校验 |
| 容量拐点 | **40 个 HOT 房间** | 总吞吐降至 366.72 QPS，单房间 9.17 QPS，P95 升至 138.25 ms |

结论：

- 当前单实例部署的最高稳定端到端观测值为 **413.42 QPS**，对应约
  **20 个 HOT 房间**。
- `42.19 QPS` 和 `44.45 bid/s` 都是单连接顺序测试，数量接近是正常的；
  二者衡量的是单次请求延迟，不是并发容量。单 HOT 房间在当前环境中
  约 `200 QPS` 稳定，`300 QPS` 开始出现明显排队。
- 40 个 HOT 房间时，总吞吐下降、单房间吞吐明显降低、P99 上升至
  643.02 ms，说明系统已经越过应用侧容量拐点。
- `25 bid/s` 是单个房间进入 HOT 的安全切换阈值，不是系统最大 QPS，
  也不是单实例总吞吐上限。
- Redis 原生压测约 `27k req/s`，但它只测 Redis 命令处理能力。真实
  请求还包含 HTTP、Spring、钱包校验、排行榜、事件发布和 Stream
  持久化，因此端到端结果显著低于该参考值。
- 冷路径竞争测试中的 `500 offered QPS -> 302.20 achieved QPS` 属于
  固定金额、大量请求预期返回 `400` 的队列崩塌测试，不能当作有效出价
  容量，也不能与上述 413.42 QPS 的有效出价结果混用。

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

## Single HOT Room Concurrency Test

The previous per-room `42.19 QPS` result used exactly one sequential worker
per room. That measures request latency, not the maximum throughput of one
HOT room. This test uses an open-loop request rate against one HOT room:

- One HOT room and one funded bidder
- All requests use the same amount after the first successful bid
- The first request returns `200`; the following requests are expected to
  return `400 BID_TOO_LOW`
- This measures HOT-path request handling under bidding contention, not the
  successful-bid acceptance rate
- A 3-second, 200 QPS warmup runs before each measured stage

| Offered QPS | Achieved QPS | P50 ms | P95 ms | P99 ms | Max In Flight | Result |
|---:|---:|---:|---:|---:|---:|---|
| 200 | 199.73 | 10.33 | 15.19 | 21.11 | 12 | Stable |
| 250 | 249.52 | 16.44 | 263.62 | 294.24 | 98 | Tail latency starts to degrade |
| 300 | 298.62 | 47.37 | 385.73 | 437.88 | 165 | Queue is growing |
| 350 | 345.77 | 235.67 | 599.14 | 646.12 | 255 | Overloaded |
| 400 | 325.65 | 2,695.91 | 3,464.35 | 3,542.77 | 1,438 | Queue collapse |

The same test runner and Docker network were also used to run a cold-path
contention control. Cold-path duplicate-bid rejection remains stable through
300 QPS in that control, while the HOT path starts degrading between 250 and
300 QPS. This is expected for this particular workload: a duplicate bid is
rejected before MySQL mutates the row, so it is already a cheap failure path,
while the HOT path still pays for the Redis script and cache reads.

The HOT-path advantage in this project is therefore not "one room accepts
more sequential valid bids". It is:

- Invalid or outbid requests stop contending for the MySQL room row lock.
- Independent HOT rooms can run concurrently and reached 413.42 valid bids
  per second in the multi-room test.
- Financial mutation, leaderboard update, event emission, and persistence are
  kept off the synchronous MySQL path.

For a single auction room, only one strictly increasing successful bid can win
per version. The measured single-connection valid-bid rate is therefore around
42 to 44 bids per second, while the safe concurrent HOT-path handling range in
this environment is about 200 QPS.

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

Run the single HOT room concurrency sweep:

```powershell
$env:BENCHMARK_MODE = "hot-single"
$env:BENCHMARK_DURATION_SECONDS = "10"
$env:BENCHMARK_PREHEAT_QPS = "100"
$env:BENCHMARK_PREHEAT_SECONDS = "8"
$env:BENCHMARK_WARMUP_SECONDS = "3"
$env:BENCHMARK_STAGES = "200,250,300,350,400"
node D:\auction\jmeter\bid-qps-benchmark.mjs
```
