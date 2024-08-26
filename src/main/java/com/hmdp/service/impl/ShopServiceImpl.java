package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import io.lettuce.core.GeoSearch;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //缓存击穿(互斥锁)
//        Shop shop = queryWithMutex(id);

        //缓存击穿(逻辑过期)
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    //封装缓存穿透
   /* public Shop queryWithPassThrough(Long id) {
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3. 存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //判断命中的是否是空值
        if (shopJson != null) {
            //返回错误信息
            return null;
        }


        //4.不存在，根据id查询数据库
        Shop shop = this.lambdaQuery().eq(Shop::getId, id).one();
        //5.数据库中不存在，返回错误
        if (shop == null) {

            //解决缓存穿透，将空值写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }
        //6.存在，写入redis中
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回商铺信息
        return shop;
    }*/

    //封装缓存击穿(互斥锁)
    public Shop queryWithMutex(Long id) {
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3. 存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //判断命中的是否是空值
        if (shopJson != null) {
            //返回错误信息
            return null;
        }

        //实现缓存重建
        //获取互斥锁
        Shop shop = null;
        try {
            boolean isLock = tryLock(LOCK_SHOP_KEY + id);
            //判断是否获取成功
            if (!isLock) {
                //获取失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //获取成功，根据id查询数据库
            shop = this.lambdaQuery().eq(Shop::getId, id).one();

            //模拟重建的延时
            Thread.sleep(200);
            //5.数据库中不存在，返回错误
            if (shop == null) {

                //解决缓存穿透，将空值写入redis
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);

                return null;
            }
            //6.存在，写入redis中
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unlock(LOCK_SHOP_KEY + id);
        }
        //7.返回商铺信息
        return shop;
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

//    //在Shop中加入逻辑过期时间
/*    public void saveShopToRedis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = this.lambdaQuery().eq(Shop::getId, id).one();
        Thread.sleep(100);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }*/

    /*private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //封装缓存击穿(逻辑过期方法)
    public Shop queryWithLogicalExpire(Long id) {
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3. 不存在，直接返回
            return null;
        }

        //4.存在，需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回
            return shop;
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
                    this.saveShopToRedis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(LOCK_SHOP_KEY + id);
                }
            });
        }
        //返回过期的商铺信息
        return shop;
    }*/


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        this.updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1. 判断是否需要根据距离查询
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //2. 计算分页查询参数
        int from = (current - 1) * SystemConstants.MAX_PAGE_SIZE;
        int end = current * SystemConstants.MAX_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        //3. 查询redis、按照距离排序、分页; 结果：shopId、distance
        //GEOSEARCH key FROMLONLAT x y BYRADIUS 5000 m WITHDIST
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        //4. 解析出id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() < from) {
            //起始查询位置大于数据总量，则说明没数据了，返回空集合
            return Result.ok(Collections.emptyList());
        }
        ArrayList<Long> ids = new ArrayList<>(list.size());
        HashMap<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        //5. 根据id查询shop
        String idsStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD( id," + idsStr + ")").list();
        for (Shop shop : shops) {
            //设置shop的举例属性，从distanceMap中根据shopId查询
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //6. 返回
        return Result.ok(shops);
    }
}
