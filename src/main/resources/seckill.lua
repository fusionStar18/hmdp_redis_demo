--1.参数
--1.1优惠券id
local voucherId = ARGV[1]

--1.2用户id
local userId = ARGV[2]

--1.2订单id
local orderId = ARGV[3]

--2.数据key
--2.1库存key
local stockKey = 'seckill:stock:' .. voucherId

--2.2订单key
local orderKey = 'seckill:order:' .. voucherId

--3.脚本业务
--3.1判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    --3.2库存不足，返回1
    return 1
end

--3.2判断业务是否下单 SISMEMBER orderKey userID
if (redis.call('sismember', orderKey, userId) == 1) then
    --存在，是重复下单
    return 2
end
--3.4 扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
--3.5 下单 saa orderKey userID
redis.call('sadd', orderKey, userId)

--3.6 发送消息到队列当中 XADD stream.orders * k1 v1 k2 v2...
redis.call('xadd','stream.orders','*','userId' ,userId,'voucherId',voucherId,'id',orderId)
return 0
