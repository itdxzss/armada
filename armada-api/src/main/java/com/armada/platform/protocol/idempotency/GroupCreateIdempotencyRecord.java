package com.armada.platform.protocol.idempotency;

import com.armada.platform.protocol.model.result.GroupCreateResult;

import java.util.Objects;

/**
 * 建群操作在 Redis 中保存的幂等状态。
 *
 * @param status 状态
 * @param claimToken 本次原子领取令牌
 * @param result 首次成功结果，处理中为空
 */
public record GroupCreateIdempotencyRecord(
        Status status,
        String claimToken,
        GroupCreateResult result) {

    public GroupCreateIdempotencyRecord {
        status = Objects.requireNonNull(status, "status 不能为空");
        if (claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException("claimToken 不能为空");
        }
        if (status == Status.SUCCEEDED && result == null) {
            throw new IllegalArgumentException("成功记录必须包含建群结果");
        }
    }

    public static GroupCreateIdempotencyRecord processing(String claimToken) {
        return new GroupCreateIdempotencyRecord(Status.PROCESSING, claimToken, null);
    }

    public static GroupCreateIdempotencyRecord succeeded(
            String claimToken,
            GroupCreateResult result) {
        return new GroupCreateIdempotencyRecord(
                Status.SUCCEEDED, claimToken, Objects.requireNonNull(result));
    }

    /** 建群幂等状态。 */
    public enum Status {
        PROCESSING,
        SUCCEEDED
    }
}
