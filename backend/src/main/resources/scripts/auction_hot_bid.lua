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
local walletKeyPrefix = ARGV[7]
local walletTtlSeconds = tonumber(ARGV[8])

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

local bidderWalletKey = walletKeyPrefix .. userId
if redis.call("EXISTS", bidderWalletKey) == 0 then
    return "ERR|BIDDER_WALLET_MISSING"
end

local bidderBalance = tonumber(redis.call("HGET", bidderWalletKey, "balance")) or 0
local bidderFrozenAmount = tonumber(redis.call("HGET", bidderWalletKey, "frozenAmount")) or 0
local requiredReserve = amount
if previousLeaderUserId == userId and bidCount > 0 then
    requiredReserve = amount - previousAmount
end
if requiredReserve < 0 then
    requiredReserve = 0
end

local minNextBid = startPrice
if bidCount > 0 then
    minNextBid = currentPrice + stepPrice
end

if amount < minNextBid then
    return "ERR|BID_TOO_LOW|" .. string.format("%.2f", minNextBid)
end

if bidderBalance < requiredReserve then
    return "ERR|INSUFFICIENT_FUNDS|" .. string.format("%.2f", requiredReserve)
end

if previousLeaderUserId ~= "" and previousLeaderUserId ~= userId and previousAmount > 0 then
    local previousLeaderWalletKey = walletKeyPrefix .. previousLeaderUserId
    if redis.call("EXISTS", previousLeaderWalletKey) == 1 then
        local previousLeaderBalance = tonumber(redis.call("HGET", previousLeaderWalletKey, "balance")) or 0
        local previousLeaderFrozen = tonumber(redis.call("HGET", previousLeaderWalletKey, "frozenAmount")) or 0
        local updatedPreviousFrozen = previousLeaderFrozen - previousAmount
        if updatedPreviousFrozen < 0 then
            updatedPreviousFrozen = 0
        end
        redis.call("HSET", previousLeaderWalletKey,
            "balance", string.format("%.2f", previousLeaderBalance + previousAmount),
            "frozenAmount", string.format("%.2f", updatedPreviousFrozen),
            "updatedAtEpochMilli", tostring(nowMillis)
        )
        redis.call("EXPIRE", previousLeaderWalletKey, walletTtlSeconds)
    end
end

redis.call("HSET", bidderWalletKey,
    "balance", string.format("%.2f", bidderBalance - requiredReserve),
    "frozenAmount", string.format("%.2f", bidderFrozenAmount + requiredReserve),
    "updatedAtEpochMilli", tostring(nowMillis)
)
redis.call("EXPIRE", bidderWalletKey, walletTtlSeconds)

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
