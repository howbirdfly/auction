local leaderboardKey = KEYS[1]
local profileKey = KEYS[2]
local limit = tonumber(ARGV[1])

local tuples = redis.call("ZREVRANGE", leaderboardKey, 0, limit - 1, "WITHSCORES")
local result = {}
local rank = 1
for index = 1, #tuples, 2 do
    local userId = tuples[index]
    local amount = tuples[index + 1]
    local nickname = redis.call("HGET", profileKey, userId) or userId
    table.insert(result, tostring(rank) .. "\t" .. userId .. "\t" .. nickname .. "\t" .. amount)
    rank = rank + 1
end
return table.concat(result, "\n")
