package com.armada.platform.protocol.port;
import com.armada.platform.protocol.model.command.CreatorDeletionCommand;
import com.armada.platform.protocol.model.command.CreatorDeletionObservationRequest;
import com.armada.platform.protocol.model.result.CreatorDeletionResult;
import com.armada.platform.protocol.model.result.CreatorDeletionObservation;
/** Android 主设备删号与独立新鲜证据查询；调用方必须先持久化操作。 */
public interface CreatorAccountDeletionPort {
    /** 单次永久删除；超时后只能 query，不允许自动重发。 */
    CreatorDeletionResult delete(CreatorDeletionCommand command);
    /** 原 operationId 的只读对账。 */
    CreatorDeletionResult query(CreatorDeletionCommand command);
    /** 管理员新鲜查询群及注册状态，结构异常必须抛出。 */
    CreatorDeletionObservation observe(CreatorDeletionObservationRequest request);
}
