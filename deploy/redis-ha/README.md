# Redis Sentinel HA

This directory contains a local Docker topology for demonstrating one Redis
master, two replicas, and three Sentinels.

The six containers must run on three or more physical hosts in production.
Running everything on one Docker host only demonstrates failover and does not
protect against host failure.

## Start

```powershell
docker compose -f D:\auction\deploy\redis-ha\docker-compose.yml up -d
docker compose -f D:\auction\deploy\redis-ha\docker-compose.yml ps
```

## Verify replication

```powershell
docker exec auction-redis-master redis-cli -a auction123 info replication
docker exec auction-redis-replica-1 redis-cli -a auction123 info replication
docker exec auction-redis-replica-2 redis-cli -a auction123 info replication
```

The primary reports two connected replicas. Both replicas report
`master_link_status:up`.

## Verify Sentinel

```powershell
docker exec auction-redis-sentinel-1 redis-cli -p 26379 -a sentinel123 sentinel get-master-addr-by-name auction-master
docker exec auction-redis-sentinel-2 redis-cli -p 26379 -a sentinel123 sentinel get-master-addr-by-name auction-master
docker exec auction-redis-sentinel-3 redis-cli -p 26379 -a sentinel123 sentinel get-master-addr-by-name auction-master
```

All Sentinels should return the same master address.

## Test failover

```powershell
docker stop auction-redis-master
Start-Sleep -Seconds 8
docker exec auction-redis-sentinel-1 redis-cli -p 26379 -a sentinel123 sentinel get-master-addr-by-name auction-master
docker exec auction-redis-sentinel-2 redis-cli -p 26379 -a sentinel123 sentinel get-master-addr-by-name auction-master
docker exec auction-redis-sentinel-3 redis-cli -p 26379 -a sentinel123 sentinel get-master-addr-by-name auction-master
```

One replica becomes the new master. Restarting the old master makes it rejoin
as a replica:

```powershell
docker start auction-redis-master
```

## Connect Spring Boot

The compose file announces the host machine's reachable IP and published Redis
ports to Sentinel. Set `REDIS_HOST_IP` to the Windows WLAN/Ethernet address
before starting the stack. The default is the address currently used by this
workspace, but it changes when the machine joins another network.

```powershell
$env:REDIS_HOST_IP = "192.168.3.59"
docker compose -f D:\auction\deploy\redis-ha\docker-compose.yml up -d
```

Use the settings in `application-sentinel.yml.example`, or override them with
environment variables:

```powershell
$env:SPRING_DATA_REDIS_SENTINEL_MASTER = "auction-master"
$env:SPRING_DATA_REDIS_SENTINEL_NODES = "127.0.0.1:26379,127.0.0.1:26380,127.0.0.1:26381"
$env:SPRING_DATA_REDIS_SENTINEL_PASSWORD = "sentinel123"
$env:SPRING_DATA_REDIS_PASSWORD = "auction123"
$env:SPRING_DATA_REDIS_DATABASE = "2"
```

Keep all auction price, wallet, request idempotency, and Stream reads/writes on
the master. Replicas are for failover and backup, not for real-time auction
reads.

## Stop

```powershell
docker compose -f D:\auction\deploy\redis-ha\docker-compose.yml down
```

Add `-v` only when you intentionally want to delete Redis data volumes.
