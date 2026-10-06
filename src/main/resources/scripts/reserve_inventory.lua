-- reserve_inventory.lua
-- KEYS[1] = inventory_key (e.g., flash-sale:1:inventory)
-- KEYS[2] = user_set_key (e.g., flash-sale:1:users)
-- KEYS[3] = idempotency_key (e.g., idempotency:xyz-123)
-- ARGV[1] = quantity (integer)
-- ARGV[2] = user_id (string)
-- ARGV[3] = ttl (integer for idempotency and user set, e.g., 86400)

-- Check Idempotency First
if redis.call('EXISTS', KEYS[3]) == 1 then
    return -1 -- DUPLICATE_REQUEST
end

-- Check if user already bought
if redis.call('SISMEMBER', KEYS[2], ARGV[2]) == 1 then
    return -2 -- USER_ALREADY_RESERVED
end

-- Check stock
local stockStr = redis.call('GET', KEYS[1])
if not stockStr then
    return -3 -- INVALID_OR_NOT_STARTED
end

local stock = tonumber(stockStr)
local quantity = tonumber(ARGV[1])

if stock < quantity then
    return 0 -- SOLD_OUT
end

-- Deduct stock
redis.call('DECRBY', KEYS[1], quantity)

-- Add user to set and set TTL if new
redis.call('SADD', KEYS[2], ARGV[2])
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))

-- Save idempotency key
redis.call('SETEX', KEYS[3], tonumber(ARGV[3]), 'RESERVED')

return 1 -- SUCCESS
