package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {

    @Resource
    private  StringRedisTemplate stringRedisTemplate;

    public void set(String key , Object value, Long time, TimeUnit unit){

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);

    }

    public void setWithLogicalExpire(String key , Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        //1.从redis查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3. 存在，直接返回
            return JSONUtil.toBean(json,type);
        }

        //判断命中的是否是空值
        if (json != null) {
            //返回错误信息
            return null;
        }


        //4.不存在，根据id查询数据库
        R r  = dbFallback.apply(id);
        //5.数据库中不存在，返回错误
        if (r == null) {

            //解决缓存穿透，将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }
        //6.存在，写入redis中
        this.set(key,r,time,unit);
        //7.返回商铺信息
        return r;
    }

    //获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //封装缓存击穿(逻辑过期方法)
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit) {
        //1.从redis查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(json)) {
            //3. 存在，直接返回
            return null;
        }

        //4.存在，需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回
            return r;
        }

        //过期，需要缓存重建
        //获取互斥锁
        boolean isLock = tryLock(LOCK_SHOP_KEY + id);
        //判断是否获取到互斥锁
        if (isLock) {
            //获取到，开启新线程实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                //重建缓存
                try {
                    //查询数据库
                    R rl = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,rl,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(LOCK_SHOP_KEY + id);
                }
            });
        }
        //返回过期的商铺信息
        return r;
    }
}
