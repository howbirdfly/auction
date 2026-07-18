local stateKey = KEYS[1]
local leaderboardKey = KEYS[2]
local leaderboardProfileKey = KEYS[3]
local recentBidKey = KEYS[4]

local statusClosed = ARGV[1]
local nowMillis = tonumber(ARGV[2])
local userId = ARGV[3]
local nickname = ARGV[4]
local amount = tonumber(ARGV[5])
local hotBufferSeconds = tonumber(ARGV[6])

if redis.call("EXISTS", stateKey) == 0 then
    return "ERR|ROOM_MISSING"
end

local status = redis.call("HGET", stateKey, "status")
if status == statusClosed then
    return "ERR|ROOM_CLOSED"
end

local endsAtMillis = tonumber(redis.call("HGET", stateKey, "endsAtEpochMilli"))
if nowMillis > endsAtMillis then
    return "ERR|ROOM_EXPIRED"
end

local startPrice = tonumber(redis.call("HGET", stateKey, "startPrice"))
local currentPrice = tonumber(redis.call("HGET", stateKey, "currentPrice"))
local stepPrice = tonumber(redis.call("HGET", stateKey, "stepPrice"))
local bidCount = tonumber(redis.call("HGET", stateKey, "bidCount")) or 0
local version = tonumber(redis.call("HGET", stateKey, "version")) or 0
local previousLeaderUserId = redis.call("HGET", stateKey, "leaderUserId") or ""
local previousAmount = 0
if bidCount > 0 then
    previousAmount = currentPrice
end

local minNextBid = startPrice
if bidCount > 0 then
    minNextBid = currentPrice + stepPrice
end

if amount < minNextBid then
    return "ERR|BID_TOO_LOW|" .. string.format("%.2f", minNextBid)
end

local newBidCount = bidCount + 1
local newMinNextBid = amount + stepPrice
local newVersion = version + 1
local ttlSeconds = math.max(1, math.ceil((endsAtMillis - nowMillis) / 1000) + hotBufferSeconds)

redis.call("HSET", stateKey,
    "currentPrice", string.format("%.2f", amount),
    "leaderUserId", userId,
    "leaderNickname", nickname,
    "endsAtEpochMilli", tostring(endsAtMillis),
    "minNextBid", string.format("%.2f", newMinNextBid),
    "bidCount", tostring(newBidCount),
    "version", tostring(newVersion)
)
redis.call("EXPIRE", stateKey, ttlSeconds)

redis.call("ZADD", leaderboardKey, amount, userId)
redis.call("EXPIRE", leaderboardKey, ttlSeconds)
redis.call("HSET", leaderboardProfileKey, userId, nickname)
redis.call("EXPIRE", leaderboardProfileKey, ttlSeconds)

local recentBidPayload = cjson.encode({
    userId = userId,
    nickname = nickname,
    amount = string.format("%.2f", amount),
    version = newVersion,
    bidTimeEpochMilli = nowMillis
})
redis.call("LPUSH", recentBidKey, recentBidPayload)
redis.call("LTRIM", recentBidKey, 0, 9)
redis.call("EXPIRE", recentBidKey, ttlSeconds)

return "OK|" .. tostring(endsAtMillis) .. "|" .. tostring(newBidCount) .. "|" .. string.format("%.2f", newMinNextBid) .. "|" .. tostring(newVersion) .. "|" .. previousLeaderUserId .. "|" .. string.format("%.2f", previousAmount)
