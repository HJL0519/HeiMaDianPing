package com.hmdp.utils;

public interface ILock {

    //尝试获取锁
    //timeoutsec 锁持有的超时时间，过期后自动释放
    //true代表获取锁成功，false代表获取锁失败
    boolean tryLock(long timeoutSec);

    //释放锁
    void unlock();
}
