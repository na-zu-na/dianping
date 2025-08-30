package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        Shop shop = queryWithPassThrough(id);


        //Shop shop=queryWithMutex(id);

        //Shop shop = queryWithLogicalExpire(id);
        return Result.ok(shop);

    }

    @Override
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        String key=CACHE_SHOP_KEY+id;
        updateById(shop);
        stringRedisTemplate.delete(key);
        return Result.ok(shop);
    }

    public Shop queryWithPassThrough(Long id){
        String key=CACHE_SHOP_KEY+id;
        String shopJson=stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            //log.debug(shopJson);
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            //log.debug(shop.toString());
            return shop;
        }
        if (shopJson != null) {
            return null;
        }
        Shop shop=getById(id);
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key,"", CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return null;
        }
        shopJson=JSONUtil.toJsonStr(shop);
        //log.debug(shopJson);
        stringRedisTemplate.opsForValue().set(key, shopJson,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    public Shop queryWithMutex(Long id){
        String key=CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        Shop shop=null;
        try {
            boolean bool=getLock(id);
            if (bool){
                shop = getById(id);
                if (shop == null) {
                    stringRedisTemplate.opsForValue().set(key,"", CACHE_SHOP_TTL, TimeUnit.MINUTES);
                    return null;
                }
                stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

                return shop;
            }
            Thread.sleep(50);
            return queryWithMutex(id);
        }
        catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        finally {
            unlock(id);
        }

    }

    public ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id){
        String key=CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (!StrUtil.isNotBlank(shopJson)) {
            return null;
        }
        RedisData redisData=JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop=JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), Shop.class);
        if (!LocalDateTime.now().isAfter(redisData.getExpireTime())){
            return shop;
        }

        boolean bool= false;
        try {
            bool = getLock(id);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (bool){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try{
                    this.save2Redis(id,20L);
                }
                catch (Exception e){
                    throw new RuntimeException(e);
                }
                finally {
                    unlock(id);
                }
            });
        }
        return shop;
    }

    public void save2Redis(Long id,Long expireTime){
        String key=CACHE_SHOP_KEY+id;
        Shop shop=getById(id);
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    public boolean getLock(Long id) throws InterruptedException {
        String lockKey=LOCK_SHOP_KEY+id;
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }

    public void unlock(Long id){
        String lockKey=LOCK_SHOP_KEY+id;
        stringRedisTemplate.delete(lockKey);
    }
}
