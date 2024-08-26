package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //判断是关注还是取关
        if (isFollow) {
            //关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = this.save(follow);
            if(isSuccess){
                //把关注用户的id放入redis的set集合 sadd userid followerUserId
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        } else {
            //取关，删除 delete from tb_follow where userId = ? and follow_user = ？
            boolean isSuccess = this.remove(new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, followUserId));
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //查询是否关注 select from tb_follow where userId = ? and follow_user = ？
        Long userId = UserHolder.getUser().getId();
        Long count = this.lambdaQuery().eq(Follow::getId, userId).eq(Follow::getFollowUserId, followUserId).count();
        //判断
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //求交集
        String key1 = "follows:" + userId;
        String key2 = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //解析id
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询用户
        List<User> users = userService.lambdaQuery().in(User::getId, ids).list();
        //转换类型
        List<UserDTO> userDTOS = BeanUtil.copyToList(users, UserDTO.class);

        return Result.ok(userDTOS);
    }


}
