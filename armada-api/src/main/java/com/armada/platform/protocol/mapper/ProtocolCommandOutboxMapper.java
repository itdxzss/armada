package com.armada.platform.protocol.mapper;

import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 协议命令 Outbox 数据访问。
 *
 * <p>写入 pending 命令时由租户拦截器注入 tenant_id;publisher 后台扫描和状态流转不依赖
 * HTTP 租户上下文,因此 dispatch/mark 方法显式关闭租户拦截器,并按状态和锁上下文精确更新。</p>
 */
@Mapper
public interface ProtocolCommandOutboxMapper {

    /**
     * 批量插入待发送命令。
     *
     * <p>tenant_id 由租户拦截器注入,调用方必须在租户上下文内执行。rows 为空时调用方应跳过,
     * 避免生成空 INSERT。</p>
     *
     * @param rows 待插入命令行,状态应为 PENDING
     * @return 插入行数
     */
    int batchInsertPending(@Param("rows") List<ProtocolCommandOutbox> rows);

    /**
     * 按状态和可重试时间扫描可发送命令。
     *
     * <p>publisher 后台任务跨租户扫描,不读取 {@code TenantContext};后续发送前仍可通过行内
     * tenant_id 恢复租户上下文。</p>
     *
     * @param status 目标状态码
     * @param now    当前 epoch 毫秒
     * @param limit  最大返回行数
     * @return 按 next_retry_at/id 升序排列的命令行
     */
    @InterceptorIgnore(tenantLine = "true")
    List<ProtocolCommandOutbox> selectDispatchable(@Param("status") int status,
                                                   @Param("now") long now,
                                                   @Param("limit") int limit);

    /**
     * 将 PENDING 命令抢占为 LOCKED。
     *
     * @param ids      待抢占 outbox id;为空时直接返回 0
     * @param lockedBy publisher 实例标识
     * @param lockedAt 抢占时间(epoch 毫秒),也作为 updated_at 和 next_retry_at 到期门禁
     * @return 实际抢占行数
     */
    default int markLocked(List<Long> ids, String lockedBy, long lockedAt) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return markLockedInternal(
                ids,
                ProtocolCommandOutboxStatus.PENDING.code(),
                ProtocolCommandOutboxStatus.LOCKED.code(),
                lockedBy,
                lockedAt);
    }

    /**
     * 将 PENDING 命令抢占为 LOCKED 的底层 SQL 映射。
     *
     * @param ids           待抢占 outbox id
     * @param pendingStatus PENDING 状态码
     * @param lockedStatus  LOCKED 状态码
     * @param lockedBy      publisher 实例标识
     * @param lockedAt      抢占时间(epoch 毫秒),未到 next_retry_at 的行不会被抢占
     * @return 实际抢占行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int markLockedInternal(@Param("ids") List<Long> ids,
                           @Param("pendingStatus") int pendingStatus,
                           @Param("lockedStatus") int lockedStatus,
                           @Param("lockedBy") String lockedBy,
                           @Param("lockedAt") long lockedAt);

    /**
     * 按 command_id 将刚插入的 PENDING 命令抢占为 LOCKED。
     *
     * <p>afterCommit 主路径使用内存中的 outbox rows 发送 Kafka,因此只需要按 command_id 做
     * 状态抢占,不需要再全局扫描 outbox。</p>
     *
     * @param commandIds 待抢占 command_id;为空时直接返回 0
     * @param lockedBy   publisher 实例标识
     * @param lockedAt   抢占时间(epoch 毫秒),也作为 updated_at 和 next_retry_at 到期门禁
     * @return 实际抢占行数
     */
    default int markLockedByCommandIds(List<String> commandIds, String lockedBy, long lockedAt) {
        if (commandIds == null || commandIds.isEmpty()) {
            return 0;
        }
        return markLockedByCommandIdsInternal(
                commandIds,
                ProtocolCommandOutboxStatus.PENDING.code(),
                ProtocolCommandOutboxStatus.LOCKED.code(),
                lockedBy,
                lockedAt);
    }

    /**
     * 按 command_id 将 PENDING 命令抢占为 LOCKED 的底层 SQL 映射。
     *
     * @param commandIds    待抢占 command_id
     * @param pendingStatus PENDING 状态码
     * @param lockedStatus  LOCKED 状态码
     * @param lockedBy      publisher 实例标识
     * @param lockedAt      抢占时间(epoch 毫秒),未到 next_retry_at 的行不会被抢占
     * @return 实际抢占行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int markLockedByCommandIdsInternal(@Param("commandIds") List<String> commandIds,
                                       @Param("pendingStatus") int pendingStatus,
                                       @Param("lockedStatus") int lockedStatus,
                                       @Param("lockedBy") String lockedBy,
                                       @Param("lockedAt") long lockedAt);

    /**
     * 读取当前 dispatcher 已抢占成功的 outbox 行。
     *
     * @param ids      候选 outbox id
     * @param lockedBy dispatcher 实例标识
     * @param lockedAt 本次抢占时间(epoch 毫秒)
     * @return 已由当前 dispatcher 抢占的 outbox 行
     */
    default List<ProtocolCommandOutbox> selectLockedBy(List<Long> ids, String lockedBy, long lockedAt) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return selectLockedByInternal(ids, ProtocolCommandOutboxStatus.LOCKED.code(), lockedBy, lockedAt);
    }

    /**
     * 读取当前 dispatcher 已抢占成功 outbox 行的底层 SQL 映射。
     *
     * @param ids          候选 outbox id
     * @param lockedStatus LOCKED 状态码
     * @param lockedBy     dispatcher 实例标识
     * @param lockedAt     本次抢占时间(epoch 毫秒)
     * @return 已由当前 dispatcher 抢占的 outbox 行
     */
    @InterceptorIgnore(tenantLine = "true")
    List<ProtocolCommandOutbox> selectLockedByInternal(@Param("ids") List<Long> ids,
                                                       @Param("lockedStatus") int lockedStatus,
                                                       @Param("lockedBy") String lockedBy,
                                                       @Param("lockedAt") long lockedAt);

    /**
     * 按 command_id 读取当前 dispatcher 已抢占成功的 outbox 行。
     *
     * <p>仅用于 afterCommit 主路径遇到部分抢占时的并发兜底。正常全量抢占成功时不查库,
     * 直接使用内存 rows 发送 Kafka。</p>
     *
     * @param commandIds 候选 command_id
     * @param lockedBy   dispatcher 实例标识
     * @param lockedAt   本次抢占时间(epoch 毫秒)
     * @return 已由当前 dispatcher 抢占的 outbox 行
     */
    default List<ProtocolCommandOutbox> selectLockedByCommandIds(List<String> commandIds,
                                                                String lockedBy,
                                                                long lockedAt) {
        if (commandIds == null || commandIds.isEmpty()) {
            return List.of();
        }
        return selectLockedByCommandIdsInternal(
                commandIds,
                ProtocolCommandOutboxStatus.LOCKED.code(),
                lockedBy,
                lockedAt);
    }

    /**
     * 按 command_id 读取当前 dispatcher 已抢占成功 outbox 行的底层 SQL 映射。
     *
     * @param commandIds    候选 command_id
     * @param lockedStatus  LOCKED 状态码
     * @param lockedBy      dispatcher 实例标识
     * @param lockedAt      本次抢占时间(epoch 毫秒)
     * @return 已由当前 dispatcher 抢占的 outbox 行
     */
    @InterceptorIgnore(tenantLine = "true")
    List<ProtocolCommandOutbox> selectLockedByCommandIdsInternal(@Param("commandIds") List<String> commandIds,
                                                                 @Param("lockedStatus") int lockedStatus,
                                                                 @Param("lockedBy") String lockedBy,
                                                                 @Param("lockedAt") long lockedAt);

    /**
     * 将当前 dispatcher 持有锁的命令标记为 SENT。
     *
     * <p>状态回写必须同时校验 locked_by 和 locked_at。这样旧发送线程在锁过期释放、又被
     * 其他实例重新抢占后,不能把新锁对应的行误标成 SENT。</p>
     *
     * @param lockedRow 包含 id 或 command_id,以及 locked_by/locked_at 的锁上下文
     * @param sentAt    Kafka producer ack 成功时间(epoch 毫秒),也作为 updated_at
     * @return 实际更新行数
     */
    default int markSent(ProtocolCommandOutbox lockedRow, long sentAt) {
        if (!hasLockContext(lockedRow)) {
            return 0;
        }
        ProtocolCommandOutbox row = stateUpdateRow(lockedRow);
        row.setSentAt(sentAt);
        row.setUpdatedAt(sentAt);
        if (row.getId() != null) {
            return markSentInternal(
                    row,
                    ProtocolCommandOutboxStatus.LOCKED.code(),
                    ProtocolCommandOutboxStatus.SENT.code());
        }
        return markSentByCommandIdInternal(
                row,
                ProtocolCommandOutboxStatus.LOCKED.code(),
                ProtocolCommandOutboxStatus.SENT.code());
    }

    /**
     * 将 LOCKED 命令标记为 SENT 的底层 SQL 映射。
     *
     * @param row          包含 id/lockedBy/lockedAt/sentAt/updatedAt 的状态更新载体
     * @param lockedStatus LOCKED 状态码
     * @param sentStatus   SENT 状态码
     * @return 实际更新行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int markSentInternal(@Param("row") ProtocolCommandOutbox row,
                         @Param("lockedStatus") int lockedStatus,
                         @Param("sentStatus") int sentStatus);

    /**
     * 按 command_id 标记 SENT 的底层 SQL 映射。
     *
     * @param row          包含 commandId/lockedBy/lockedAt/sentAt/updatedAt 的状态更新载体
     * @param lockedStatus LOCKED 状态码
     * @param sentStatus   SENT 状态码
     * @return 实际更新行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int markSentByCommandIdInternal(@Param("row") ProtocolCommandOutbox row,
                                    @Param("lockedStatus") int lockedStatus,
                                    @Param("sentStatus") int sentStatus);

    /**
     * 将当前 dispatcher 持有锁的命令释放回 PENDING 等待后续重试。
     *
     * @param lockedRow   包含 id 或 command_id,以及 locked_by/locked_at 的锁上下文
     * @param nextRetryAt 下次可重试时间(epoch 毫秒)
     * @param lastError   最近一次发送失败原因
     * @param updatedAt   更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    default int markRetry(ProtocolCommandOutbox lockedRow, long nextRetryAt, String lastError, long updatedAt) {
        if (!hasLockContext(lockedRow)) {
            return 0;
        }
        ProtocolCommandOutbox row = stateUpdateRow(lockedRow);
        row.setNextRetryAt(nextRetryAt);
        row.setLastError(lastError);
        row.setUpdatedAt(updatedAt);
        if (row.getId() != null) {
            return markRetryInternal(
                    row,
                    ProtocolCommandOutboxStatus.LOCKED.code(),
                    ProtocolCommandOutboxStatus.PENDING.code());
        }
        return markRetryByCommandIdInternal(
                row,
                ProtocolCommandOutboxStatus.LOCKED.code(),
                ProtocolCommandOutboxStatus.PENDING.code());
    }

    /**
     * 将 LOCKED 命令释放回 PENDING 的底层 SQL 映射。
     *
     * @param row           包含 id/lockedBy/lockedAt/nextRetryAt/lastError/updatedAt 的状态更新载体
     * @param lockedStatus  LOCKED 状态码
     * @param pendingStatus PENDING 状态码
     * @return 实际更新行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int markRetryInternal(@Param("row") ProtocolCommandOutbox row,
                          @Param("lockedStatus") int lockedStatus,
                          @Param("pendingStatus") int pendingStatus);

    /**
     * 按 command_id 释放回 PENDING 的底层 SQL 映射。
     *
     * @param row           包含 commandId/lockedBy/lockedAt/nextRetryAt/lastError/updatedAt 的状态更新载体
     * @param lockedStatus  LOCKED 状态码
     * @param pendingStatus PENDING 状态码
     * @return 实际更新行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int markRetryByCommandIdInternal(@Param("row") ProtocolCommandOutbox row,
                                     @Param("lockedStatus") int lockedStatus,
                                     @Param("pendingStatus") int pendingStatus);

    /**
     * 将当前 dispatcher 持有锁的命令标记为 DEAD。
     *
     * @param lockedRow 包含 id 或 command_id,以及 locked_by/locked_at 的锁上下文
     * @param lastError 不可恢复失败原因
     * @param updatedAt 更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    default int markDead(ProtocolCommandOutbox lockedRow, String lastError, long updatedAt) {
        if (!hasLockContext(lockedRow)) {
            return 0;
        }
        ProtocolCommandOutbox row = stateUpdateRow(lockedRow);
        row.setLastError(lastError);
        row.setUpdatedAt(updatedAt);
        if (row.getId() != null) {
            return markDeadInternal(
                    row,
                    ProtocolCommandOutboxStatus.LOCKED.code(),
                    ProtocolCommandOutboxStatus.DEAD.code());
        }
        return markDeadByCommandIdInternal(
                row,
                ProtocolCommandOutboxStatus.LOCKED.code(),
                ProtocolCommandOutboxStatus.DEAD.code());
    }

    /**
     * 将 LOCKED 命令标记为 DEAD 的底层 SQL 映射。
     *
     * @param row          包含 id/lockedBy/lockedAt/lastError/updatedAt 的状态更新载体
     * @param lockedStatus LOCKED 状态码
     * @param deadStatus   DEAD 状态码
     * @return 实际更新行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int markDeadInternal(@Param("row") ProtocolCommandOutbox row,
                         @Param("lockedStatus") int lockedStatus,
                         @Param("deadStatus") int deadStatus);

    /**
     * 按 command_id 标记 DEAD 的底层 SQL 映射。
     *
     * @param row          包含 commandId/lockedBy/lockedAt/lastError/updatedAt 的状态更新载体
     * @param lockedStatus LOCKED 状态码
     * @param deadStatus   DEAD 状态码
     * @return 实际更新行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int markDeadByCommandIdInternal(@Param("row") ProtocolCommandOutbox row,
                                    @Param("lockedStatus") int lockedStatus,
                                    @Param("deadStatus") int deadStatus);

    /**
     * 将超时 LOCKED 命令释放回 PENDING。
     *
     * @param lockedBefore locked_at 早于该时间的行视为超时(epoch 毫秒)
     * @param now          恢复时间(epoch 毫秒),也作为 next_retry_at 和 updated_at
     * @param lastError    恢复原因
     * @param limit        单次最多恢复行数
     * @return 实际恢复行数
     */
    default int releaseExpiredLocks(long lockedBefore, long now, String lastError, int limit) {
        if (limit <= 0) {
            return 0;
        }
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setLockedAt(lockedBefore);
        row.setNextRetryAt(now);
        row.setLastError(lastError);
        row.setUpdatedAt(now);
        return releaseExpiredLocksInternal(
                row,
                ProtocolCommandOutboxStatus.LOCKED.code(),
                ProtocolCommandOutboxStatus.PENDING.code(),
                limit);
    }

    /**
     * 将超时 LOCKED 命令释放回 PENDING 的底层 SQL 映射。
     *
     * @param row           包含 lockedAt/nextRetryAt/lastError/updatedAt 的恢复载体
     * @param lockedStatus  LOCKED 状态码
     * @param pendingStatus PENDING 状态码
     * @param limit         单次最多恢复行数
     * @return 实际恢复行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int releaseExpiredLocksInternal(@Param("row") ProtocolCommandOutbox row,
                                    @Param("lockedStatus") int lockedStatus,
                                    @Param("pendingStatus") int pendingStatus,
                                    @Param("limit") int limit);

    private static boolean hasLockContext(ProtocolCommandOutbox row) {
        if (row == null || row.getLockedBy() == null || row.getLockedBy().isBlank() || row.getLockedAt() == null) {
            return false;
        }
        return row.getId() != null || (row.getCommandId() != null && !row.getCommandId().isBlank());
    }

    private static ProtocolCommandOutbox stateUpdateRow(ProtocolCommandOutbox lockedRow) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setId(lockedRow.getId());
        row.setCommandId(lockedRow.getCommandId());
        row.setLockedBy(lockedRow.getLockedBy());
        row.setLockedAt(lockedRow.getLockedAt());
        return row;
    }
}
