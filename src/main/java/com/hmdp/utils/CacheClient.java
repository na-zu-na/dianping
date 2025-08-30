package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;

    public void set(Long id, Object value, Long time, TimeUnit timeUnit ){
        stringRedisTemplate.opsForValue().set(String.valueOf(id), String.valueOf(value), time, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit ){
        RedisData redisData=new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit timeUnit ){
        String key=CACHE_SHOP_KEY+id;
        String json =stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }
        R r=dbFallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key,"", time, timeUnit);
            return null;
        }
        json =JSONUtil.toJsonStr(r);
        stringRedisTemplate.opsForValue().set(key, json,time, timeUnit);
        return r;
    }

    public <R,ID> R queryWithMutex(ID id,Class<R> type,Long time, TimeUnit timeUnit,Function<ID,R> dbFallback){
        String key=CACHE_SHOP_KEY+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        R r=null;
        try {
            boolean bool=getLock(id);
            if (bool){
                r = dbFallback.apply(id);
                if (r == null) {
                    stringRedisTemplate.opsForValue().set(key,"", time, timeUnit);
                    return null;
                }
                stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r), time, timeUnit);

                return r;
            }
            Thread.sleep(50);
            return queryWithMutex(id, type, time, timeUnit, dbFallback);
        }
        catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        finally {
            unlock(id);
        }

    }

    public <ID> boolean getLock(ID id) throws InterruptedException {
        String lockKey=LOCK_SHOP_KEY+id;
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }

    public <ID> void unlock(ID id){
        String lockKey=LOCK_SHOP_KEY+id;
        stringRedisTemplate.delete(lockKey);
    }
}
