package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private IVoucherOrderService proxy;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private static final ExecutorService SECKILL_ORDER_EXCUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private  void init(){
        SECKILL_ORDER_EXCUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){

                try {
                    //获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常,{}",e);
                }
            }
        }
    }



    @Override
    public Result SeckillVoucher(Long voucherId) {

        //获取用户
        Long userId = UserHolder.getUser().getId();

        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );

        //2.判断结果是否为0
        int res = result.intValue();
        if (res != 0){
            //2.1.不为0，代表没有购买资格
            return Result.fail(res == 1 ? "库存不足" : "不能重复下单");
        }

        //2.2.为0，有购买资格，把下单信息保存至阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();

        //2.3 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        //2.4 用户id
        voucherOrder.setUserId(userId);

        //2.5 代金券id
        voucherOrder.setVoucherId(voucherId);

        //2.6 放入阻塞队列
        orderTasks.add(voucherOrder);

        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //3.返回订单id
        return Result.ok(orderId);
    }

    // @Override
    // public Result SeckillVoucher(Long voucherId) {
    //
    //     //1.查询优惠券
    //     SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
    //
    //     //2.判断秒杀是否开始
    //     LocalDateTime beginTime = voucher.getBeginTime();
    //     if(LocalDateTime.now().isBefore(beginTime)){
    //         return Result.fail("活动还未开始！");
    //     }
    //
    //     //3.判断秒杀是否已经结束
    //     LocalDateTime endTime = voucher.getEndTime();
    //     if(LocalDateTime.now().isAfter(endTime)){
    //         return Result.fail("活动已经结束！");
    //     }
    //
    //     //4.判断库存是否充足
    //     int stock = voucher.getStock();
    //     if (stock < 1){
    //         return Result.fail("库存不足！");
    //     }
    //
    //     Long userId = UserHolder.getUser().getId();
    //     // synchronized (userId.toString().intern()) {
    //     //     //获取事务代理对象
    //     //     IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
    //     //     return proxy.createVoucher(voucherId);
    //     // }
    //
    //     //创建锁对象
    //     //SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate,"order:" + userId);
    //     RLock lock = redissonClient.getLock("lock:order:" + userId);
    //     //获取锁
    //     boolean flag = lock.tryLock();
    //     //判断是否获取锁成功
    //     if (!flag){
    //         //获取锁失败,返回错误或重试
    //         return Result.fail("不允许重复下单！");
    //     }
    //     try {
    //         //获取事务代理对象
    //         IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
    //         return proxy.createVoucher(voucherId);
    //     }finally {
    //         //释放锁
    //         lock.unlock();
    //     }
    //
    // }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        //查询订单
        Long userId = voucherOrder.getUserId();
        //判断是否存在
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
        if (count > 0){
            //用户已经购买过了
            log.error("用户已经购买过了");
        }

        //5.扣减库存
        //cas法实现乐观锁
        boolean flag = seckillVoucherService.update()
                .setSql("stock = stock - 1") //set stock = stock -1
                .eq("voucher_id", voucherOrder).gt("stock",0) //where id = ? and stock > 0
                .update();
        if(!flag){
            log.error("库存不足！");
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {

        //获取用户id
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order" + userId);
        //获取锁
        boolean flag = lock.tryLock();
        //判断是否获取锁成功
        if (!flag){
             //获取锁失败，返回错误或重试
            log.error("不允许重复下单！");
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            //释放锁
            lock.unlock();
        }

    }
}
