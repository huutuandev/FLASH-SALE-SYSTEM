-- rate_limiter.lua
-- KEYS[1] = rate_limit_key
-- ARGV[1] = limit
-- ARGV[2] = expire_time (usually 1 second)

local current = redis.call('GET', KEYS[1])
if current and tonumber(current) >= tonumber(ARGV[1]) then
    return 0 -- Rate limited
end

current = redis.call('INCR', KEYS[1])
if tonumber(current) == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[2])
end

return 1 -- Allowed
