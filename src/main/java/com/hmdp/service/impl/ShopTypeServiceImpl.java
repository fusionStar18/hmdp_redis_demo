package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        //1.从redis查询商铺列表缓存

        List<String> lists = stringRedisTemplate.opsForList().range(CACHE_SHOPTYPE_KEY, 0, -1); //返回的是json字符串
        List<ShopType> typeList = new ArrayList<>();
        //2.判断是否存在
        if (!lists.isEmpty()) {
            //3. 存在，直接返回(我们要返回的一定是对象，所以需要将JSON字符串转换，遍历一下，逐一转换，再存到我们上面刚定义的存对象的集合中，最后返回)
            for(String list:lists){
                ShopType shopType = JSONUtil.toBean(list, ShopType.class);
                typeList.add(shopType);
            }
            return Result.ok(typeList);
        }

        //4.不存在，去数据库查询
        List<ShopType> shopTypeList = this.lambdaQuery().orderByAsc(ShopType::getSort).list();
        //5.数据库中不存在，返回错误
        if(shopTypeList.isEmpty()){
            return Result.fail("不存在该分类");
        }
        //6.存在，写入redis中(将shopType转成json)
        //在此之前，先把对象转换成JSON(依旧是遍历，将查询到的list集合中每个对象都转换成JSON字符串，再用lists集合存好，lists是用来存JSON的噢)
        for (ShopType shopType:shopTypeList){
            String string = JSONUtil.toJsonStr(shopType);
            lists.add(string);
        }

        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOPTYPE_KEY,lists);

        //返回给客户端
        return Result.ok(shopTypeList);
    }
}
