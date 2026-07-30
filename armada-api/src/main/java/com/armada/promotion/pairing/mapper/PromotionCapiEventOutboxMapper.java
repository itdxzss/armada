package com.armada.promotion.pairing.mapper;

import com.armada.promotion.pairing.model.entity.PromotionCapiEventOutbox;
import com.armada.promotion.pairing.model.enums.PromotionCapiEventStatus;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 推广正式 CAPI 事件 Outbox 数据访问。 */
@Mapper
public interface PromotionCapiEventOutboxMapper {

    int batchInsert(@Param("rows") List<PromotionCapiEventOutbox> rows);

    default int activate(long pairingSessionId, int eventStage, long eventTime, long now) {
        return activateInternal(
                pairingSessionId, eventStage,
                PromotionCapiEventStatus.WAITING.code(),
                PromotionCapiEventStatus.PENDING.code(), eventTime, now);
    }

    int activateInternal(@Param("pairingSessionId") long pairingSessionId,
                         @Param("eventStage") int eventStage,
                         @Param("waitingStatus") int waitingStatus,
                         @Param("pendingStatus") int pendingStatus,
                         @Param("eventTime") long eventTime,
                         @Param("now") long now);

    default int cancelWaiting(long pairingSessionId, long now) {
        return cancelWaitingInternal(
                pairingSessionId,
                PromotionCapiEventStatus.WAITING.code(),
                PromotionCapiEventStatus.CANCELED.code(), now);
    }

    int cancelWaitingInternal(@Param("pairingSessionId") long pairingSessionId,
                              @Param("waitingStatus") int waitingStatus,
                              @Param("canceledStatus") int canceledStatus,
                              @Param("now") long now);

    default List<PromotionCapiEventOutbox> selectDispatchable(long now, int limit) {
        return selectDispatchableInternal(PromotionCapiEventStatus.PENDING.code(), now, limit);
    }

    @InterceptorIgnore(tenantLine = "true")
    List<PromotionCapiEventOutbox> selectDispatchableInternal(@Param("pendingStatus") int pendingStatus,
                                                               @Param("now") long now,
                                                               @Param("limit") int limit);

    default int markLocked(List<Long> ids, String lockedBy, long lockedAt) {
        if (ids == null || ids.isEmpty()) return 0;
        return markLockedInternal(ids, PromotionCapiEventStatus.PENDING.code(),
                PromotionCapiEventStatus.LOCKED.code(), lockedBy, lockedAt);
    }

    @InterceptorIgnore(tenantLine = "true")
    int markLockedInternal(@Param("ids") List<Long> ids,
                           @Param("pendingStatus") int pendingStatus,
                           @Param("lockedStatus") int lockedStatus,
                           @Param("lockedBy") String lockedBy,
                           @Param("lockedAt") long lockedAt);

    default List<PromotionCapiEventOutbox> selectLocked(
            List<Long> ids, String lockedBy, long lockedAt) {
        if (ids == null || ids.isEmpty()) return List.of();
        return selectLockedInternal(ids, PromotionCapiEventStatus.LOCKED.code(), lockedBy, lockedAt);
    }

    @InterceptorIgnore(tenantLine = "true")
    List<PromotionCapiEventOutbox> selectLockedInternal(@Param("ids") List<Long> ids,
                                                        @Param("lockedStatus") int lockedStatus,
                                                        @Param("lockedBy") String lockedBy,
                                                        @Param("lockedAt") long lockedAt);

    default int markSent(PromotionCapiEventOutbox row, long sentAt) {
        return markSentInternal(row, PromotionCapiEventStatus.LOCKED.code(),
                PromotionCapiEventStatus.SENT.code(), sentAt);
    }

    int markSentInternal(@Param("row") PromotionCapiEventOutbox row,
                         @Param("lockedStatus") int lockedStatus,
                         @Param("sentStatus") int sentStatus,
                         @Param("sentAt") long sentAt);

    default int markRetry(PromotionCapiEventOutbox row, long nextRetryAt,
                          String errorCode, String errorMessage, long now) {
        return markRetryInternal(row, PromotionCapiEventStatus.LOCKED.code(),
                PromotionCapiEventStatus.PENDING.code(), nextRetryAt, errorCode, errorMessage, now);
    }

    int markRetryInternal(@Param("row") PromotionCapiEventOutbox row,
                          @Param("lockedStatus") int lockedStatus,
                          @Param("pendingStatus") int pendingStatus,
                          @Param("nextRetryAt") long nextRetryAt,
                          @Param("errorCode") String errorCode,
                          @Param("errorMessage") String errorMessage,
                          @Param("now") long now);

    default int markDead(PromotionCapiEventOutbox row, String errorCode,
                         String errorMessage, long now) {
        return markDeadInternal(row, PromotionCapiEventStatus.LOCKED.code(),
                PromotionCapiEventStatus.DEAD.code(), errorCode, errorMessage, now);
    }

    int markDeadInternal(@Param("row") PromotionCapiEventOutbox row,
                         @Param("lockedStatus") int lockedStatus,
                         @Param("deadStatus") int deadStatus,
                         @Param("errorCode") String errorCode,
                         @Param("errorMessage") String errorMessage,
                         @Param("now") long now);

    default int releaseExpiredLocks(long lockedBefore, long now, int limit) {
        return releaseExpiredLocksInternal(
                PromotionCapiEventStatus.LOCKED.code(),
                PromotionCapiEventStatus.PENDING.code(), lockedBefore, now, limit);
    }

    @InterceptorIgnore(tenantLine = "true")
    int releaseExpiredLocksInternal(@Param("lockedStatus") int lockedStatus,
                                    @Param("pendingStatus") int pendingStatus,
                                    @Param("lockedBefore") long lockedBefore,
                                    @Param("now") long now,
                                    @Param("limit") int limit);

    default int scrubExpiredSensitive(long now, long lockedBefore, int limit) {
        return scrubExpiredSensitiveInternal(
                PromotionCapiEventStatus.WAITING.code(),
                PromotionCapiEventStatus.PENDING.code(),
                PromotionCapiEventStatus.LOCKED.code(),
                PromotionCapiEventStatus.CANCELED.code(),
                PromotionCapiEventStatus.DEAD.code(),
                now, lockedBefore, limit);
    }

    @InterceptorIgnore(tenantLine = "true")
    int scrubExpiredSensitiveInternal(
            @Param("waitingStatus") int waitingStatus,
            @Param("pendingStatus") int pendingStatus,
            @Param("lockedStatus") int lockedStatus,
            @Param("canceledStatus") int canceledStatus,
            @Param("deadStatus") int deadStatus,
            @Param("now") long now,
            @Param("lockedBefore") long lockedBefore,
            @Param("limit") int limit);
}
