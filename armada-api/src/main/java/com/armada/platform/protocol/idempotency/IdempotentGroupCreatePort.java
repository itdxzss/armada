package com.armada.platform.protocol.idempotency;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.port.GroupCreatePort;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 在协议后端路由外层提供严格建群幂等。
 *
 * <p>可能已经创建群组的异常会保留 PROCESSING，阻止相同 operationId 再次建群。</p>
 */
public final class IdempotentGroupCreatePort implements GroupCreatePort {

    private static final String OPERATION = "group.create";
    private static final int MAX_CLAIM_ATTEMPTS = 2;
    private static final Set<ProtocolErrorCode> DEFINITELY_NOT_CREATED = EnumSet.of(
            ProtocolErrorCode.BAD_REQUEST,
            ProtocolErrorCode.ACCOUNT_NOT_ONLINE,
            ProtocolErrorCode.UNSUPPORTED_BACKEND,
            ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED);

    private final GroupCreatePort delegate;
    private final GroupCreateIdempotencyStore store;

    public IdempotentGroupCreatePort(
            GroupCreatePort delegate,
            GroupCreateIdempotencyStore store) {
        this.delegate = delegate;
        this.store = store;
    }

    @Override
    public GroupCreateResult create(GroupCreateCommand command) {
        String operationId = command.operationId();
        for (int attempt = 0; attempt < MAX_CLAIM_ATTEMPTS; attempt++) {
            Optional<GroupCreateIdempotencyRecord> current = find(operationId, command);
            if (current.isPresent()) {
                return resolveExisting(current.get(), command);
            }

            String claimToken = UUID.randomUUID().toString();
            if (tryBegin(operationId, claimToken, command)) {
                return invokeClaimed(command, claimToken);
            }

            Optional<GroupCreateIdempotencyRecord> raced = find(operationId, command);
            if (raced.isPresent()) {
                return resolveExisting(raced.get(), command);
            }
        }
        throw unconfirmed(command, "建群幂等领取结果无法确认", null);
    }

    private GroupCreateResult invokeClaimed(GroupCreateCommand command, String claimToken) {
        try {
            GroupCreateResult result = delegate.create(command);
            try {
                store.saveSuccess(command.operationId(), claimToken, result);
            } catch (RuntimeException exception) {
                throw unconfirmed(command, "建群成功但幂等结果保存失败", exception);
            }
            return result;
        } catch (ProtocolException exception) {
            if (exception.errorCode() == ProtocolErrorCode.GROUP_CREATE_RESULT_UNCONFIRMED) {
                throw exception;
            }
            if (DEFINITELY_NOT_CREATED.contains(exception.errorCode())) {
                clearProcessing(command, claimToken);
                throw exception;
            }
            throw unconfirmed(command, "建群结果无法确认", exception);
        } catch (RuntimeException exception) {
            throw unconfirmed(command, "建群结果无法确认", exception);
        }
    }

    private GroupCreateResult resolveExisting(
            GroupCreateIdempotencyRecord record,
            GroupCreateCommand command) {
        if (record.status() == GroupCreateIdempotencyRecord.Status.SUCCEEDED) {
            return record.result();
        }
        throw unconfirmed(command, "相同建群操作正在处理或结果未确认", null);
    }

    private Optional<GroupCreateIdempotencyRecord> find(
            String operationId,
            GroupCreateCommand command) {
        try {
            return store.find(operationId);
        } catch (RuntimeException exception) {
            throw storeUnavailable(command, exception);
        }
    }

    private boolean tryBegin(
            String operationId,
            String claimToken,
            GroupCreateCommand command) {
        try {
            return store.tryBegin(operationId, claimToken);
        } catch (RuntimeException exception) {
            throw storeUnavailable(command, exception);
        }
    }

    private void clearProcessing(GroupCreateCommand command, String claimToken) {
        try {
            store.clearProcessing(command.operationId(), claimToken);
        } catch (RuntimeException exception) {
            throw storeUnavailable(command, exception);
        }
    }

    private static ProtocolException storeUnavailable(
            GroupCreateCommand command,
            RuntimeException cause) {
        return new ProtocolException(
                ProtocolErrorCode.IDEMPOTENCY_STORE_UNAVAILABLE,
                "建群幂等存储不可用",
                cause).withContext(command.account().backend(), OPERATION, command.operationId());
    }

    private static ProtocolException unconfirmed(
            GroupCreateCommand command,
            String message,
            Throwable cause) {
        return new ProtocolException(
                ProtocolErrorCode.GROUP_CREATE_RESULT_UNCONFIRMED,
                message,
                cause).withContext(command.account().backend(), OPERATION, command.operationId());
    }
}
