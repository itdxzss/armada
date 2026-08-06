package com.armada.group.normalcreation.support;

/** 账号互斥锁在业务动作结束前失去所有权；调用结果不得按普通失败自动重放。 */
public class NormalGroupCreationLockLostException extends RuntimeException {

    public NormalGroupCreationLockLostException(String message) {
        super(message);
    }
}
