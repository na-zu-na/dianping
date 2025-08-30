package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefershTokenIntercepter implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;
    public RefershTokenIntercepter(StringRedisTemplate template){
        this.stringRedisTemplate=template;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader("authorization");
        if (token==null){
            return true;
        }
        String tokenKey=LOGIN_USER_KEY+token;

        Map<Object, Object> objectMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        if (objectMap.isEmpty()){
            return true;
        }
        UserDTO userDTO= BeanUtil.fillBeanWithMap(objectMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
