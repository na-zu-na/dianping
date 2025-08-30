package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    RedissonClient redissonClient;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

//    @Override
//    public Result seckSkillVoucher(Long voucherId) {
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        LocalDateTime beginTime = voucher.getBeginTime();
//        LocalDateTime endTime = voucher.getEndTime();
//        if (LocalDateTime.now().isBefore(beginTime) || LocalDateTime.now().isAfter(endTime)) {
//            return Result.fail("不在活动范围");
//        }
//        int stock = voucher.getStock();
//        if (stock<1){
//            return Result.fail("库存不足！");
//        }
//        boolean success = seckillVoucherService.update().setSql("stock=stock-1").
//                eq("voucher_id", voucherId).gt("stock",0).update();
//        if (!success){
//            return Result.fail("库存不足");
//        }
//
//        Long userId=UserHolder.getUser().getId();
//        RLock rLock=redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = rLock.tryLock();
//        if (!isLock){
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            rLock.unlock();
//        }
//    }



    public void createVoucherOrder(VoucherOrder voucherId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            Result.fail("每个用户只能下一单");
            return;
        }

        Long id = redisIdWorker.nextId("order");

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId.getId());
        voucherOrder.setUserId(userId);
        voucherOrder.setId(id);
        save(voucherOrder);

        Result.ok(id);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(), voucherId.toString(), String.valueOf(userId), String.valueOf(orderId));
        int r=result.intValue();
        if (r!=0){
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        return Result.ok(orderId);
    }

    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while(true){
                try{
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    if (list==null || list.isEmpty()){
                        continue;
                    }
                    MapRecord<String, Object, Object> mapRecord = list.get(0);
                    Map<Object,Object> value=mapRecord.getValue();
                    VoucherOrder voucherOrder= BeanUtil.fillBeanWithMap(value,new VoucherOrder(),true);
                    createVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge("s1","g1",mapRecord.getId());
                }
                catch (Exception e){
                    log.error(e.getMessage());
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        while(true){
            try {
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create("stream.orders", ReadOffset.from("0"))
                );
                if (list==null || list.isEmpty()){
                    break;
                }
                MapRecord<String, Object, Object> mapRecord = list.get(0);
                Map<Object, Object> value = mapRecord.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 3.创建订单
                createVoucherOrder(voucherOrder);
                // 4.确认消息 XACK
                stringRedisTemplate.opsForStream().acknowledge("s1", "g1", mapRecord.getId());
            } catch (Exception e) {
                log.error(e.getMessage());
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    e.printStackTrace();
                }
            }
        }
    }
}
