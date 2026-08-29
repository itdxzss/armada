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
    int REGULAR_RETENTION_CLASS = 0;
    int HYPERLINK_RETENTION_CLASS = 1;

    /** 按显式租户和原 commandId 重排已投递消息命令，不插入第二行。 */
    @InterceptorIgnore(tenantLine = "true")
    int replayMessageCommand(@Param("tenantId") long tenantId,
            @Param("commandId") String commandId,
            @Param("messageCommandType") String messageCommandType,
            @Param("replayableStatuses") List<Integer> replayableStatuses,
            @Param("pendingStatus") int pendingStatus,
            @Param("now") long now);

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

    /** 单批删除普通 SENT 命令，明确排除需要独立保留期的超链 recipient。 */
    @InterceptorIgnore(tenantLine = "true")
    default int deleteRegularSentBefore(long createdBefore, int limit) {
        return deleteRegularSentBeforeInternal(
                ProtocolCommandOutboxStatus.SENT.code(), REGULAR_RETENTION_CLASS, createdBefore, limit);
    }

    /** 单批删除超过独立保留期的超链 recipient SENT 命令。 */
    @InterceptorIgnore(tenantLine = "true")
    default int deleteHyperlinkSentBefore(long createdBefore, int limit) {
        return deleteHyperlinkSentBeforeInternal(
                ProtocolCommandOutboxStatus.SENT.code(), HYPERLINK_RETENTION_CLASS,
                createdBefore, limit);
    }

    @InterceptorIgnore(tenantLine = "true")
    int deleteRegularSentBeforeInternal(@Param("sentStatus") int sentStatus,
            @Param("retentionClass") int retentionClass,
            @Param("createdBefore") long createdBefore, @Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true")
    int deleteHyperlinkSentBeforeInternal(@Param("sentStatus") int sentStatus,
            @Param("retentionClass") int retentionClass,
            @Param("createdBefore") long createdBefore, @Param("limit") int limit);

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
     * @param expectedStatuses 允许收敛为 SENT 的 DISPATCHING/CANCEL_REQUESTED 状态码
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
     * 将当前 dispatcher 持有的 LOCKED 命令以 CAS 提交为 DISPATCHING。
     *
     * <p>LOCKED 是可取消的预抢占态；本更新是发送与业务结束之间的裁决点。
     * 业务结束先将行取消时，本方法返回 0，调用方不得发送。</p>
     *
     * @param lockedRows 必须具有相同 locked_by/locked_at 的命令行
     * @param now 提交发送的时间(epoch 毫秒)
     * @return 实际提交发送的行数
     */
    default int markDispatching(List<ProtocolCommandOutbox> lockedRows, long now) {
        if (lockedRows == null || lockedRows.isEmpty()) {
            return 0;
        }
        ProtocolCommandOutbox first = lockedRows.get(0);
        if (!hasCommandLockContext(first)) {
            return 0;
        }
        boolean sameLock = lockedRows.stream().allMatch(row ->
                hasCommandLockContext(row)
                        && first.getLockedBy().equals(row.getLockedBy())
                        && first.getLockedAt().equals(row.getLockedAt()));
        if (!sameLock) {
            return 0;
        }
        return markDispatchingByCommandIdsInternal(
                lockedRows.stream().map(ProtocolCommandOutbox::getCommandId).toList(),
                first.getLockedBy(),
                first.getLockedAt(),
                ProtocolCommandOutboxStatus.LOCKED.code(),
                ProtocolCommandOutboxStatus.DISPATCHING.code(),
                now);
    }

    /** 将当前预抢占命令提交为 DISPATCHING 的底层 SQL 映射。 */
    @InterceptorIgnore(tenantLine = "true")
    int markDispatchingByCommandIdsInternal(
            @Param("commandIds") List<String> commandIds,
            @Param("lockedBy") String lockedBy,
            @Param("lockedAt") long lockedAt,
            @Param("lockedStatus") int lockedStatus,
            @Param("dispatchingStatus") int dispatchingStatus,
            @Param("now") long now);

    /** 部分 CAS 命中时，只读取当前 dispatcher 已提交发送的命令。 */
    default List<ProtocolCommandOutbox> selectDispatchingByCommandIds(
            List<String> commandIds, String lockedBy, long lockedAt) {
        if (commandIds == null || commandIds.isEmpty()) {
            return List.of();
        }
        return selectDispatchingByCommandIdsInternal(
                commandIds,
                ProtocolCommandOutboxStatus.DISPATCHING.code(),
                lockedBy,
                lockedAt);
    }

    /** 按锁上下文读取 DISPATCHING 命令的底层 SQL 映射。 */
    @InterceptorIgnore(tenantLine = "true")
    List<ProtocolCommandOutbox> selectDispatchingByCommandIdsInternal(
            @Param("commandIds") List<String> commandIds,
            @Param("dispatchingStatus") int dispatchingStatus,
            @Param("lockedBy") String lockedBy,
            @Param("lockedAt") long lockedAt);

    /**
     * 将同一发送窗口内、由当前 Dispatcher 持锁的命令批量标记为 SENT。
     *
     * <p>正常 afterCommit 主路径的内存行可能没有数据库主键，因此统一按全局唯一 command_id 更新。
     * 所有行必须属于同一组 locked_by 和 locked_at；任一行缺少锁上下文或锁不一致时不执行 SQL，
     * 防止旧发送线程误更新后来重新抢占的命令。</p>
     *
     * @param lockedRows 同一发送窗口内 Kafka ACK 成功的 DISPATCHING outbox 行
     * @param sentAt Kafka producer ACK 收敛时间(epoch 毫秒)，也作为 updated_at
     * @return 实际更新行数
     */
    default int markSentBatch(List<ProtocolCommandOutbox> lockedRows, long sentAt) {
        if (lockedRows == null || lockedRows.isEmpty()) {
            return 0;
        }
        ProtocolCommandOutbox first = lockedRows.get(0);
        if (!hasCommandLockContext(first)) {
            return 0;
        }
        boolean sameLock = lockedRows.stream().allMatch(row ->
                hasCommandLockContext(row)
                        && first.getLockedBy().equals(row.getLockedBy())
                        && first.getLockedAt().equals(row.getLockedAt()));
        if (!sameLock) {
            return 0;
        }
        ProtocolCommandOutbox state = stateUpdateRow(first);
        state.setSentAt(sentAt);
        state.setUpdatedAt(sentAt);
        return markSentBatchByCommandIdsInternal(
                lockedRows.stream().map(ProtocolCommandOutbox::getCommandId).toList(),
                state,
                List.of(
                        ProtocolCommandOutboxStatus.DISPATCHING.code(),
                        ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code()),
                ProtocolCommandOutboxStatus.SENT.code());
    }

    /**
     * 按 command_id 批量标记 SENT 的底层 SQL 映射。
     *
     * @param commandIds 当前发送窗口内 ACK 成功的 command_id
     * @param row 包含 lockedBy/lockedAt/sentAt/updatedAt 的状态更新载体
     * @param lockedStatus LOCKED 状态码
     * @param sentStatus SENT 状态码
     * @return 实际更新行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int markSentBatchByCommandIdsInternal(@Param("commandIds") List<String> commandIds,
                                          @Param("row") ProtocolCommandOutbox row,
                                          @Param("expectedStatuses") List<Integer> expectedStatuses,
                                          @Param("sentStatus") int sentStatus);

    /**
     * 将当前 dispatcher 已提交发送、但明确发送失败的命令释放回 PENDING。
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
        if (cancelRequested(row, ProtocolCommandOutboxStatus.CANCELED.code()) == 1) {
            return 0;
        }
        if (row.getId() != null) {
            return markRetryInternal(
                    row,
                    ProtocolCommandOutboxStatus.DISPATCHING.code(),
                    ProtocolCommandOutboxStatus.PENDING.code());
        }
        return markRetryByCommandIdInternal(
                row,
                ProtocolCommandOutboxStatus.DISPATCHING.code(),
                ProtocolCommandOutboxStatus.PENDING.code());
    }

    /**
     * 将 DISPATCHING 命令释放回 PENDING 的底层 SQL 映射。
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
     * 任务释放时，仅取消当前租户下尚未被 publisher 抢占的营销消息。
     *
     * @param tenantId 当前租户 ID
     * @param marketingTaskId 营销任务 ID
     * @param now 当前时间（epoch 毫秒）
     * @return 实际取消的命令数
     */
    default int cancelPendingMarketingTaskCommands(Long tenantId, Long marketingTaskId, long now) {
        return cancelPendingMarketingTaskCommandsInternal(
                tenantId,
                marketingTaskId,
                ProtocolCommandOutboxStatus.PENDING.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(),
                now);
    }

    /**
     * 按租户和任务取消 PENDING 营销命令的底层联表更新。
     *
     * <p>联表更新关闭租户插件并在 SQL 中显式约束两张表的 tenant_id，
     * 避免释放操作跨租户命中。</p>
     *
     * @param tenantId 当前租户 ID
     * @param marketingTaskId 营销任务 ID
     * @param pendingStatus PENDING 状态码
     * @param canceledStatus CANCELED 状态码
     * @param now 当前时间（epoch 毫秒）
     * @return 实际取消的命令数
     */
    @InterceptorIgnore(tenantLine = "true")
    int cancelPendingMarketingTaskCommandsInternal(
            @Param("tenantId") Long tenantId,
            @Param("marketingTaskId") Long marketingTaskId,
            @Param("pendingStatus") int pendingStatus,
            @Param("canceledStatus") int canceledStatus,
            @Param("now") long now);

    /**
     * 取消当前租户指定账号尚未发布的上线命令。
     *
     * @param accountIds 账号 ID 列表
     * @param aggregateType 账号聚合类型
     * @param onlineCommandType 账号上线命令类型
     * @param pendingStatus PENDING 状态码
     * @param canceledStatus CANCELED 状态码
     * @param now 当前时间(epoch 毫秒)
     * @return 实际取消行数
     */
    int cancelPendingAccountOnlineCommandsInternal(
            @Param("accountIds") List<Long> accountIds,
            @Param("aggregateType") String aggregateType,
            @Param("onlineCommandType") String onlineCommandType,
            @Param("pendingStatus") int pendingStatus,
            @Param("canceledStatus") int canceledStatus,
            @Param("now") long now);

    /**
     * 按普通拉群任务/执行行范围取消 PENDING 命令；聚合类型与状态全部由 Java 传入。
     */
    int cancelPendingPullTaskCommandsInternal(
            @Param("taskId") long taskId,
            @Param("executionId") Long executionId,
            @Param("accountActionAggregateType") String accountActionAggregateType,
            @Param("pullCallAggregateType") String pullCallAggregateType,
            @Param("materialAggregateType") String materialAggregateType,
            @Param("memberQueryAggregateType") String memberQueryAggregateType,
            @Param("cancelableStatuses") List<Integer> cancelableStatuses,
            @Param("dispatchingStatus") int dispatchingStatus,
            @Param("canceledStatus") int canceledStatus,
            @Param("cancelRequestedStatus") int cancelRequestedStatus,
            @Param("lastError") String lastError,
            @Param("now") long now);

    /**
     * 将当前 dispatcher 已提交发送但确认无法恢复的命令标记为 DEAD。
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
        if (cancelRequested(row, ProtocolCommandOutboxStatus.CANCELED.code()) == 1) {
            return 0;
        }
        if (row.getId() != null) {
            return markDeadInternal(
                    row,
                    ProtocolCommandOutboxStatus.DISPATCHING.code(),
                    ProtocolCommandOutboxStatus.DEAD.code());
        }
        return markDeadByCommandIdInternal(
                row,
                ProtocolCommandOutboxStatus.DISPATCHING.code(),
                ProtocolCommandOutboxStatus.DEAD.code());
    }

    /**
     * 将 DISPATCHING 命令标记为 DEAD 的底层 SQL 映射。
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

    /**
     * 将超时 DISPATCHING 收敛为 DEAD。
     *
     * <p>DISPATCHING 表示发送权已提交；实例崩溃后无法判定 Kafka 是否已收到，
     * 因此不能像 LOCKED 一样自动重发，避免重复执行真实协议副作用。</p>
     */
    default int markExpiredDispatchingDead(
            long dispatchingBefore, long now, String lastError, int limit) {
        if (limit <= 0) {
            return 0;
        }
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setLastError(lastError);
        row.setUpdatedAt(now);
        return markExpiredDispatchingDeadInternal(
                row,
                dispatchingBefore,
                ProtocolCommandOutboxStatus.DISPATCHING.code(),
                ProtocolCommandOutboxStatus.DEAD.code(),
                limit);
    }

    /** 将超时 DISPATCHING 收敛为 DEAD 的底层 SQL 映射。 */
    @InterceptorIgnore(tenantLine = "true")
    int markExpiredDispatchingDeadInternal(
            @Param("row") ProtocolCommandOutbox row,
            @Param("dispatchingBefore") long dispatchingBefore,
            @Param("dispatchingStatus") int dispatchingStatus,
            @Param("deadStatus") int deadStatus,
            @Param("limit") int limit);

    /** 将超时 CANCEL_REQUESTED 收敛为 CANCELED，且不重新发送。 */
    default int markExpiredCancelRequestedCanceled(
            long requestedBefore, long now, String lastError, int limit) {
        if (limit <= 0) {
            return 0;
        }
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setLastError(lastError);
        row.setUpdatedAt(now);
        return markExpiredCancelRequestedCanceledInternal(
                row,
                requestedBefore,
                ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(),
                limit);
    }

    /** 将超时 CANCEL_REQUESTED 收敛为 CANCELED 的底层 SQL 映射。 */
    @InterceptorIgnore(tenantLine = "true")
    int markExpiredCancelRequestedCanceledInternal(
            @Param("row") ProtocolCommandOutbox row,
            @Param("requestedBefore") long requestedBefore,
            @Param("cancelRequestedStatus") int cancelRequestedStatus,
            @Param("canceledStatus") int canceledStatus,
            @Param("limit") int limit);

    private int cancelRequested(ProtocolCommandOutbox row, int canceledStatus) {
        if (row.getId() != null) {
            return cancelRequestedInternal(
                    row, ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code(), canceledStatus);
        }
        return cancelRequestedByCommandIdInternal(
                row, ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code(), canceledStatus);
    }

    /** 失败结果遇到已请求结束的命令时，收敛为 CANCELED。 */
    @InterceptorIgnore(tenantLine = "true")
    int cancelRequestedInternal(
            @Param("row") ProtocolCommandOutbox row,
            @Param("cancelRequestedStatus") int cancelRequestedStatus,
            @Param("canceledStatus") int canceledStatus);

    /** 按 command_id 收敛已请求结束的失败命令。 */
    @InterceptorIgnore(tenantLine = "true")
    int cancelRequestedByCommandIdInternal(
            @Param("row") ProtocolCommandOutbox row,
            @Param("cancelRequestedStatus") int cancelRequestedStatus,
            @Param("canceledStatus") int canceledStatus);

    private static boolean hasLockContext(ProtocolCommandOutbox row) {
        if (row == null || row.getLockedBy() == null || row.getLockedBy().isBlank() || row.getLockedAt() == null) {
            return false;
        }
        return row.getId() != null || (row.getCommandId() != null && !row.getCommandId().isBlank());
    }

    private static boolean hasCommandLockContext(ProtocolCommandOutbox row) {
        return hasLockContext(row) && row.getCommandId() != null && !row.getCommandId().isBlank();
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
