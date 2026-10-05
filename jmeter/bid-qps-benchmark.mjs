const baseUrl = process.env.BENCHMARK_BASE_URL ?? "http://127.0.0.1:8080/api";
const account = process.env.BENCHMARK_ACCOUNT ?? "u10001";
const password = process.env.BENCHMARK_PASSWORD ?? "123456";
const durationSeconds = Number(process.env.BENCHMARK_DURATION_SECONDS ?? "20");
const mode = process.env.BENCHMARK_MODE ?? "contention";
const stages = (process.env.BENCHMARK_STAGES ?? "10,20,30,50,80,100,150")
  .split(",")
  .map(Number)
  .filter((value) => Number.isFinite(value) && value > 0);

let cookie = "";
let benchmarkAccount = account;

async function request(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(cookie ? { Cookie: cookie } : {}),
      ...(options.headers ?? {}),
    },
  });

  const setCookie = response.headers.get("set-cookie");
  if (setCookie) {
    cookie = setCookie.split(";")[0];
  }

  const payload = await response.json();
  if (!response.ok || payload.success === false) {
    const error = new Error(payload.message || `HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return payload.data;
}

async function setupRoom(stageQps) {
  const room = await request("/auctions", {
    method: "POST",
    body: JSON.stringify({
      itemTitle: `Benchmark ${stageQps} QPS ${Date.now()}`,
      anchorName: "Benchmark Host",
      startPrice: 1,
      stepPrice: 1,
      registrationRequired: false,
      depositAmount: 0,
      durationSeconds: 3600,
    }),
  });
  if (process.env.BENCHMARK_WARM_ROOM === "true") {
    for (let index = 0; index < 5; index += 1) {
      await request(`/auctions/${room.roomId}`);
    }
  }
  return room.roomId;
}

async function createBenchmarkUser(stageQps) {
  benchmarkAccount = `bench-${Date.now()}-${stageQps}`;
  const user = await request("/users/register", {
    method: "POST",
    body: JSON.stringify({
      account: benchmarkAccount,
      password,
      nickname: `Benchmark ${stageQps}`,
    }),
  });
  await request(`/users/${user.userId}/recharge`, {
    method: "POST",
    body: JSON.stringify({ amount: 100000 }),
  });
}

function percentile(values, ratio) {
  if (!values.length) return 0;
  const sorted = [...values].sort((left, right) => left - right);
  const index = Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1);
  return sorted[index];
}

async function runStage(stageQps) {
  if (mode === "multi") {
    return runMultiRoomStage(stageQps);
  }
  if (mode === "valid") {
    return runValidStage(stageQps);
  }
  if (mode === "threshold") {
    return runThresholdStage(stageQps);
  }
  return runContentionStage(stageQps);
}

async function runMultiRoomStage(roomCount) {
  await createBenchmarkUser(roomCount);
  const roomIds = [];
  for (let index = 0; index < roomCount; index += 1) {
    roomIds.push(await setupRoom(`${roomCount}-${index}`));
  }

  const latencies = [];
  const statuses = new Map();
  let hotResponses = 0;
  const stageStartedAt = performance.now();

  const workers = roomIds.map(async (roomId) => {
    let amount = 2;
    while ((performance.now() - stageStartedAt) / 1000 < durationSeconds) {
      const requestStartedAt = performance.now();
      let status = 0;
      try {
        const response = await request(`/auctions/${roomId}/bids`, {
          method: "POST",
          body: JSON.stringify({
            requestId: `multi-${roomCount}-${roomId}-${amount}-${Date.now()}`,
            userId: benchmarkAccount,
            nickname: `Benchmark ${roomCount}`,
            amount,
          }),
        });
        status = 200;
        if (response?.hot === true) {
          hotResponses += 1;
        }
        amount += 1;
      } catch (error) {
        status = error.status ?? 0;
      } finally {
        latencies.push(performance.now() - requestStartedAt);
        statuses.set(status, (statuses.get(status) ?? 0) + 1);
      }
    }
  });

  await Promise.all(workers);
  const elapsedSeconds = (performance.now() - stageStartedAt) / 1000;
  return {
    mode,
    rooms: roomCount,
    achievedQps: latencies.length / elapsedSeconds,
    perRoomQps: latencies.length / elapsedSeconds / roomCount,
    requests: latencies.length,
    hotResponses,
    statuses: Object.fromEntries([...statuses.entries()].sort(([a], [b]) => a - b)),
    avgMs: latencies.reduce((sum, value) => sum + value, 0) / Math.max(1, latencies.length),
    p50Ms: percentile(latencies, 0.5),
    p95Ms: percentile(latencies, 0.95),
    p99Ms: percentile(latencies, 0.99),
    maxMs: Math.max(...latencies, 0),
    maxInFlight: roomCount,
  };
}

async function runThresholdStage(targetQps) {
  await createBenchmarkUser(targetQps);
  const roomId = await setupRoom(targetQps);
  const intervalMs = 1000 / targetQps;
  const latencies = [];
  const prePromotionLatencies = [];
  const postPromotionLatencies = [];
  const statuses = new Map();
  let amount = 2;
  let nextRequestAt = performance.now();
  let promotionAtMs = null;
  const stageStartedAt = performance.now();

  while ((performance.now() - stageStartedAt) / 1000 < durationSeconds) {
    const now = performance.now();
    if (nextRequestAt > now) {
      await new Promise((resolve) => setTimeout(resolve, nextRequestAt - now));
    }

    const requestStartedAt = performance.now();
    let status = 0;
    let response = null;
    try {
      response = await request(`/auctions/${roomId}/bids`, {
        method: "POST",
        body: JSON.stringify({
          requestId: `threshold-${targetQps}-${amount}-${Date.now()}`,
          userId: benchmarkAccount,
          nickname: `Benchmark ${targetQps}`,
          amount,
        }),
      });
      status = 200;
      amount += 1;
    } catch (error) {
      status = error.status ?? 0;
    } finally {
      const latency = performance.now() - requestStartedAt;
      latencies.push(latency);
      statuses.set(status, (statuses.get(status) ?? 0) + 1);
      if (promotionAtMs == null) {
        prePromotionLatencies.push(latency);
      } else {
        postPromotionLatencies.push(latency);
      }
      if (promotionAtMs == null && response?.hot === true) {
        promotionAtMs = performance.now() - stageStartedAt;
      }
      const elapsedAfterRequest = performance.now() - stageStartedAt;
      nextRequestAt = Math.max(
        elapsedAfterRequest + intervalMs,
        nextRequestAt + intervalMs
      );
    }
  }

  return {
    mode,
    targetQps,
    offeredQps: targetQps,
    achievedQps: latencies.length / ((performance.now() - stageStartedAt) / 1000),
    requests: latencies.length,
    promoted: promotionAtMs != null,
    promotionMs: promotionAtMs,
    statuses: Object.fromEntries([...statuses.entries()].sort(([a], [b]) => a - b)),
    avgMs: latencies.reduce((sum, value) => sum + value, 0) / Math.max(1, latencies.length),
    p95Ms: percentile(latencies, 0.95),
    p99Ms: percentile(latencies, 0.99),
    prePromotionP95Ms: percentile(prePromotionLatencies, 0.95),
    postPromotionP95Ms: percentile(postPromotionLatencies, 0.95),
  };
}

async function runValidStage(stageQps) {
  await createBenchmarkUser(stageQps);
  const roomId = await setupRoom(stageQps);
  const latencies = [];
  const statuses = new Map();
  let amount = 2;
  const stageStartedAt = performance.now();

  while ((performance.now() - stageStartedAt) / 1000 < durationSeconds) {
    const requestStartedAt = performance.now();
    let status = 0;
    try {
      await request(`/auctions/${roomId}/bids`, {
        method: "POST",
        body: JSON.stringify({
          requestId: `valid-${stageQps}-${amount}-${Date.now()}`,
          userId: benchmarkAccount,
          nickname: "Benchmark Bidder",
          amount,
        }),
      });
      status = 200;
      amount += 1;
    } catch (error) {
      status = error.status ?? 0;
    } finally {
      latencies.push(performance.now() - requestStartedAt);
      statuses.set(status, (statuses.get(status) ?? 0) + 1);
    }
  }

  const elapsedSeconds = (performance.now() - stageStartedAt) / 1000;
  return {
    mode,
    offeredQps: null,
    achievedQps: latencies.length / elapsedSeconds,
    requests: latencies.length,
    statuses: Object.fromEntries([...statuses.entries()].sort(([a], [b]) => a - b)),
    avgMs: latencies.reduce((sum, value) => sum + value, 0) / Math.max(1, latencies.length),
    p50Ms: percentile(latencies, 0.5),
    p95Ms: percentile(latencies, 0.95),
    p99Ms: percentile(latencies, 0.99),
    maxMs: Math.max(...latencies, 0),
    maxInFlight: 1,
  };
}

async function runContentionStage(stageQps) {
  await createBenchmarkUser(stageQps);
  const roomId = await setupRoom(stageQps);
  const intervalMs = 1000 / stageQps;
  const totalRequests = Math.max(1, Math.round(durationSeconds * stageQps));
  const tasks = [];
  const latencies = [];
  const statuses = new Map();
  let inFlight = 0;
  let maxInFlight = 0;

  const stageStartedAt = performance.now();
  for (let index = 0; index < totalRequests; index += 1) {
    const delayMs = index * intervalMs;
    const task = new Promise((resolve) => {
      setTimeout(async () => {
        inFlight += 1;
        maxInFlight = Math.max(maxInFlight, inFlight);
        const requestStartedAt = performance.now();
        let status = 0;
        try {
          await request(`/auctions/${roomId}/bids`, {
            method: "POST",
            body: JSON.stringify({
              requestId: `bench-${stageQps}-${index}-${Date.now()}`,
              userId: benchmarkAccount,
              nickname: "Benchmark Bidder",
              amount: 100,
            }),
          });
          status = 200;
        } catch (error) {
          status = error.status ?? 0;
        } finally {
          latencies.push(performance.now() - requestStartedAt);
          statuses.set(status, (statuses.get(status) ?? 0) + 1);
          inFlight -= 1;
          resolve();
        }
      }, delayMs);
    });
    tasks.push(task);
  }

  await Promise.all(tasks);
  const elapsedSeconds = (performance.now() - stageStartedAt) / 1000;
  return {
    mode,
    offeredQps: stageQps,
    achievedQps: totalRequests / elapsedSeconds,
    requests: totalRequests,
    statuses: Object.fromEntries([...statuses.entries()].sort(([a], [b]) => a - b)),
    avgMs: latencies.reduce((sum, value) => sum + value, 0) / Math.max(1, latencies.length),
    p50Ms: percentile(latencies, 0.5),
    p95Ms: percentile(latencies, 0.95),
    p99Ms: percentile(latencies, 0.99),
    maxMs: Math.max(...latencies, 0),
    maxInFlight,
  };
}

await request("/users/login", {
  method: "POST",
  body: JSON.stringify({ account, password }),
});

const results = [];
for (const stage of stages) {
  const result = await runStage(stage);
  results.push(result);
  console.log(JSON.stringify(result));
}

console.log(JSON.stringify({ summary: results }));
