package com.armada.hyperlink.task.mapper;

import com.armada.hyperlink.task.model.entity.HyperlinkBillingReservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 任务级本地计费 Saga Mapper。 */
@Mapper
public interface HyperlinkBillingReservationMapper {
    int insert(HyperlinkBillingReservation entity);
    HyperlinkBillingReservation selectByTaskId(@Param("taskId") long taskId);
    int markReserved(@Param("taskId") long taskId, @Param("externalNo") String externalNo,
            @Param("operationKey") String operationKey, @Param("reservedAt") long reservedAt);
    int markAdjusted(@Param("taskId") long taskId, @Param("operationKey") String operationKey,
            @Param("reservedAmount") java.math.BigDecimal reservedAmount, @Param("now") long now);
    int markFailed(@Param("taskId") long taskId, @Param("failureCode") String failureCode,
            @Param("failureReason") String failureReason, @Param("nextRetryAt") long nextRetryAt,
            @Param("now") long now);
    int markPendingSettlement(@Param("taskId") long taskId,
            @Param("operationKey") String operationKey, @Param("now") long now);
    int markSettled(@Param("taskId") long taskId, @Param("operationKey") String operationKey,
            @Param("settledAmount") java.math.BigDecimal settledAmount,
            @Param("settledSendCount") long settledSendCount, @Param("now") long now);
    int markPendingRelease(@Param("taskId") long taskId,
            @Param("operationKey") String operationKey, @Param("now") long now);
    int markReleased(@Param("taskId") long taskId, @Param("operationKey") String operationKey,
            @Param("releasedAmount") java.math.BigDecimal releasedAmount, @Param("now") long now);
    int abandonUnstarted(@Param("taskId") long taskId, @Param("now") long now);
    /** 仅原子结束能够证明钱包未被调用的报价过期 RESERVE。 */
    int abandonFailedStaleUncalled(@Param("taskId") long taskId,
            @Param("failureCode") String failureCode, @Param("now") long now);
    int resetForReserve(@Param("entity") HyperlinkBillingReservation entity,
            @Param("expectedVersion") int expectedVersion);
    int resetForAdjustment(@Param("entity") HyperlinkBillingReservation entity,
            @Param("expectedVersion") int expectedVersion);
    /** CAS 替换从未调用钱包的失败 RESERVE 报价事实。 */
    int resetFailedUnstartedForReserve(@Param("entity") HyperlinkBillingReservation entity,
            @Param("expectedVersion") int expectedVersion);
}
