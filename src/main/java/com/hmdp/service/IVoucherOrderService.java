package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@EnableAspectJAutoProxy(exposeProxy = true)
public interface IVoucherOrderService extends IService<VoucherOrder> {

    //Result seckSkillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherId);

    Result seckillVoucher(Long voucherId);
}
