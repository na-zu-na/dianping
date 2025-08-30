package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest
public class HmDianPingApplicationTests {
    @Autowired
    ShopServiceImpl shopServiceImpl;

    @Test
    public void testSaveShop() {
        shopServiceImpl.save2Redis(1L,  20L);
    }

    @Resource
    private RedissonClient redissonClient;

    @Test
    public void testRedisson() throws InterruptedException {
        RLock rLock=redissonClient.getLock("any_lock");
        boolean isLock=rLock.tryLock(1,10,TimeUnit.SECONDS);
        if(isLock){
            System.out.println("执行业务");
        }
        else {
            rLock.unlock();
        }
    }

}
