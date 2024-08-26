package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog = this.lambdaQuery().eq(Blog::getId, id).one();
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        //2.查询blog有关的用户
        queryBlogUser(blog);
        //3.查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //1.获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user==null) {
            //用户未登录，无需查询是否点赞
            return;
        }
        Long userId = UserHolder.getUser().getId();
        //2.判断当前登录用户是否点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.lambdaQuery().eq(User::getId, userId).one();
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.isBlogLiked(blog);
            this.queryBlogUser(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.判断当前登录用户是否点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            //3.如果未点赞，可以点赞
            //3.1数据库点赞+1
            boolean isSuccess = this.lambdaUpdate().setSql("liked = liked+1").eq(Blog::getId, id).update();
            //3.2保存用户到redis集合 zadd key value score
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            //4如果已点赞，取消点赞
            //3.1数据库点赞-1
            boolean isSuccess = this.lambdaUpdate().setSql("liked = liked-1").eq(Blog::getId, id).update();
            //3.2把用户移出redis集合
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogByLikes(Long id) {
        //1.查询top5的点赞用户 zrange key 0 4
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key,0,4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //2.解析其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",",ids);
        //3.根据用户id查询用户
        List<User> users = userService.lambdaQuery()
                .in(User::getId, ids)
                .last("ORDER BY FIELD(id,"+idStr +")")
                .list();
        List<UserDTO> userDTOS = BeanUtil.copyToList(users, UserDTO.class);
        //4.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //获取登录用户
        UserDTO userDTO = UserHolder.getUser();
        blog.setUserId(userDTO.getId());

        //保存博文
        boolean isSuccess = this.save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        //查询笔记博主的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.lambdaQuery().eq(Follow::getFollowUserId, userDTO.getId()).list();

        //推送笔记id给所有粉丝
        for(Follow follow:follows){
            //获取粉丝id
            Long userId = follow.getUserId();
            //推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        //返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱所有的笔记进行滚动分页查询 ZREVRANGE key Max Min Limint Offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //3.解析数据blogId，最小时间戳，偏移量offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple:typedTuples){
            //获取blogId
            ids.add(Long.valueOf(tuple.getValue()));

            //获取时间戳
            long time = tuple.getScore().longValue();
            if(time == minTime) {
                os++;
            }else {
                minTime = time;
                os=1;
            }

        }
        //4.根据blogId查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = this.lambdaQuery()
                .in(Blog::getId, ids)
                .last("ORDER BY FIELD(id,"+idStr+")")
                .list();

        for (Blog blog : blogs) {
            //4.1查询blog有关的用户
            queryBlogUser(blog);
            //4.2查询blog是否被点赞
            isBlogLiked(blog);
        }

        //5.封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }
}

