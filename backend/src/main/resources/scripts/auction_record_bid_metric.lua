local currentKey = KEYS[1]
local metricPrefix = ARGV[1]
local nowSecond = tonumber(ARGV[2])
local windowSeconds = tonumber(ARGV[3])
local threshold = tonumber(ARGV[4])
local ttlSeconds = tonumber(ARGV[5])

redis.call("INCR", currentKey)
redis.call("EXPIRE", currentKey, ttlSeconds)

local total = 0
for offset = 0, windowSeconds - 1 do
    local value = redis.call("GET", metricPrefix .. tostring(nowSecond - offset))
    if value then
        total = total + tonumber(value)
    end
end

if total >= threshold * windowSeconds then
    return "1|" .. tostring(total)
end
return "0|" .. tostring(total)
