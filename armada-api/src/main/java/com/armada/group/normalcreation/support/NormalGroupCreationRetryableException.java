package com.armada.group.normalcreation.support;

/** 新建普群可安全交回 Kafka 重试的临时故障。 */
public class NormalGroupCreationRetryableException extends RuntimeException {

    public NormalGroupCreationRetryableException(String message) {
        super(message);
    }

    public NormalGroupCreationRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
