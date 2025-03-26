package com.hmdp.service.impl;

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
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {

        //获取登录的用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;

        //判断是关注还是取关
        if (isFollow){
            //关注，新增数据
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean flag = save(follow);

            if(flag){
                //把关注用户的id，放入redis的set集合
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else {
            //取关，删除
            boolean flag = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));

            if (flag){
                //把关注的用户id从redis中移除
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
            }

        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {

        //获取登录的用户
        Long userId = UserHolder.getUser().getId();

        //查询是否关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();

        return Result.ok(count > 0);
    }

    @Override
    public List<UserDTO> commonFollow(Long id) {

        List<UserDTO> list = new ArrayList<>();

        //获取当前用户的id
        Long userId = UserHolder.getUser().getId();

        String key1 = "follows:" + userId;
        String key2 = "follows:" + id;

        //求交集
        Set<String> set = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (set == null || set.isEmpty()){
            //无交集，返回空集合
            return Collections.emptyList();
        }

        //解析出id
        List<Long> ids = set.stream().map(Long::valueOf).collect(Collectors.toList());

        //查询用户
        for (Long l : ids) {
            User user = userService.getById(l);
            UserDTO userDTO = new UserDTO();
            BeanUtils.copyProperties(user,userDTO);
            list.add(userDTO);
        }

        return list;
    }
}
