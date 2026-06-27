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
 * HTTP 租户上下文,因此 dispatch/mark 方法显式关闭租户拦截器并按 id/status 精确更新。</p>
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
     * 将 LOCKED 命令标记为 SENT。
     *
     * @param id     outbox id
     * @param sentAt Kafka producer ack 成功时间(epoch 毫秒),也作为 updated_at
     * @return 实际更新行数
     */
    default int markSent(Long id, long sentAt) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setId(id);
        row.setSentAt(sentAt);
        row.setUpdatedAt(sentAt);
        return markSentInternal(
                row,
                ProtocolCommandOutboxStatus.LOCKED.code(),
                ProtocolCommandOutboxStatus.SENT.code());
    }

    /**
     * 将 LOCKED 命令标记为 SENT 的底层 SQL 映射。
     *
     * @param row          包含 id/sentAt/updatedAt 的状态更新载体
     * @param lockedStatus LOCKED 状态码
     * @param sentStatus   SENT 状态码
     * @return 实际更新行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int markSentInternal(@Param("row") ProtocolCommandOutbox row,
                         @Param("lockedStatus") int lockedStatus,
                         @Param("sentStatus") int sentStatus);

    /**
     * 将 LOCKED 命令释放回 PENDING 等待后续重试。
     *
     * @param id          outbox id
     * @param nextRetryAt 下次可重试时间(epoch 毫秒)
     * @param lastError   最近一次发送失败原因
     * @param updatedAt   更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    default int markRetry(Long id, long nextRetryAt, String lastError, long updatedAt) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setId(id);
        row.setNextRetryAt(nextRetryAt);
        row.setLastError(lastError);
        row.setUpdatedAt(updatedAt);
        return markRetryInternal(
                row,
                ProtocolCommandOutboxStatus.LOCKED.code(),
                ProtocolCommandOutboxStatus.PENDING.code());
    }

    /**
     * 将 LOCKED 命令释放回 PENDING 的底层 SQL 映射。
     *
     * @param row           包含 id/nextRetryAt/lastError/updatedAt 的状态更新载体
     * @param lockedStatus  LOCKED 状态码
     * @param pendingStatus PENDING 状态码
     * @return 实际更新行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int markRetryInternal(@Param("row") ProtocolCommandOutbox row,
                          @Param("lockedStatus") int lockedStatus,
                          @Param("pendingStatus") int pendingStatus);

    /**
     * 将 LOCKED 命令标记为 DEAD。
     *
     * @param id        outbox id
     * @param lastError 不可恢复失败原因
     * @param updatedAt 更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    default int markDead(Long id, String lastError, long updatedAt) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setId(id);
        row.setLastError(lastError);
        row.setUpdatedAt(updatedAt);
        return markDeadInternal(
                row,
                ProtocolCommandOutboxStatus.LOCKED.code(),
                ProtocolCommandOutboxStatus.DEAD.code());
    }

    /**
     * 将 LOCKED 命令标记为 DEAD 的底层 SQL 映射。
     *
     * @param row          包含 id/lastError/updatedAt 的状态更新载体
     * @param lockedStatus LOCKED 状态码
     * @param deadStatus   DEAD 状态码
     * @return 实际更新行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int markDeadInternal(@Param("row") ProtocolCommandOutbox row,
                         @Param("lockedStatus") int lockedStatus,
                         @Param("deadStatus") int deadStatus);
}
