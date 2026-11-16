redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
local count = redis.call('ZCARD', KEYS[1])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
return count
