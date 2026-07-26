package com.armada.platform.protocol.idempotency;

import com.armada.platform.protocol.model.result.GroupCreateResult;

import java.util.Optional;

/** 建群结果幂等存储。 */
public interface GroupCreateIdempotencyStore {

    Optional<GroupCreateIdempotencyRecord> find(String operationId);

    boolean tryBegin(String operationId, String claimToken);

    void saveSuccess(String operationId, String claimToken, GroupCreateResult result);

    void clearProcessing(String operationId, String claimToken);
}
