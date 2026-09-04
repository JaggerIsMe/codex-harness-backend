-- All keys are dedicated to this module. Validate budgets before any mutation.
local previous = tonumber(redis.call('HGET', KEYS[2], 'seq') or '0')
local next = tonumber(ARGV[1])
if next <= previous then return 0 end
if redis.call('HEXISTS', KEYS[2], 'closed') == 1 then return -2 end
if next ~= previous + 1 then return -1 end
local bytes = tonumber(redis.call('HGET', KEYS[2], 'bytes') or '0')
local count = redis.call('HLEN', KEYS[1])
for i=5,#ARGV,2 do
  local old = redis.call('HGET', KEYS[1], ARGV[i])
  if old then bytes = bytes - string.len(old) else count = count + 1 end
  bytes = bytes + string.len(ARGV[i+1])
end
if bytes > 16777216 or count > 1024 then return -3 end
for i=5,#ARGV,2 do redis.call('HSET', KEYS[1], ARGV[i], ARGV[i+1]) end
redis.call('HSET', KEYS[2], 'seq', ARGV[1], 'bytes', bytes)
redis.call('XADD', KEYS[3], ARGV[1]..'-0', 'data', ARGV[2])
local eventBytes = tonumber(redis.call('HGET', KEYS[2], 'eventBytes') or '0') + string.len(ARGV[2])
while redis.call('XLEN', KEYS[3]) > tonumber(ARGV[3]) or eventBytes > 8388608 do
  local oldest = redis.call('XRANGE', KEYS[3], '-', '+', 'COUNT', 1)
  if #oldest == 0 then break end
  eventBytes = eventBytes - string.len(oldest[1][2][2])
  redis.call('XDEL', KEYS[3], oldest[1][1])
end
redis.call('HSET', KEYS[2], 'eventBytes', eventBytes)
redis.call('SADD', KEYS[4], ARGV[4])
return 1
