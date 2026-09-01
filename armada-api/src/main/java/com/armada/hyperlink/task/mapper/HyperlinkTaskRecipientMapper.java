package com.armada.hyperlink.task.mapper;

import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.query.HyperlinkAccountStatCriteria;
import com.armada.hyperlink.task.model.vo.HyperlinkAccountStatRow;
import com.armada.hyperlink.task.model.vo.HyperlinkRecipientCountryCount;
import com.armada.hyperlink.task.model.vo.HyperlinkReconciliationCandidate;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 唯一 recipient 事实 Mapper。 */
@Mapper
public interface HyperlinkTaskRecipientMapper {
    long countAccountStats(@Param("criteria") HyperlinkAccountStatCriteria criteria);
    List<HyperlinkAccountStatRow> selectAccountStats(
            @Param("criteria") HyperlinkAccountStatCriteria criteria);

    /** 公网短码唯一跨租户入口；显式短码条件并锁定 recipient，禁止复用于其他查询。 */
    @InterceptorIgnore(tenantLine = "true")
    HyperlinkTaskRecipient selectByShortCodeForUpdate(@Param("shortCode") String shortCode);
    int recordPublicVisit(@Param("id") long id, @Param("firstVisit") boolean firstVisit,
            @Param("now") long now, @Param("ipAddress") byte[] ipAddress,
            @Param("userAgent") String userAgent, @Param("browser") String browser,
            @Param("os") String os, @Param("device") String device,
            @Param("language") String language, @Param("countryIso2") String countryIso2);
    long countClicked(@Param("taskId") long taskId,
            @Param("recipientPhone") String recipientPhone,
            @Param("senderPhone") String senderPhone);
    List<HyperlinkTaskRecipient> selectClickedPage(@Param("taskId") long taskId,
            @Param("recipientPhone") String recipientPhone,
            @Param("senderPhone") String senderPhone,
            @Param("sortOrder") String sortOrder,
            @Param("offset") int offset, @Param("limit") int limit);
    List<HyperlinkTaskRecipient> selectClickedExportBatch(@Param("taskId") long taskId,
            @Param("recipientPhone") String recipientPhone,
            @Param("senderPhone") String senderPhone,
            @Param("sortOrder") String sortOrder, @Param("snapshotAt") long snapshotAt,
            @Param("offset") int offset, @Param("limit") int limit);
    Long selectFirstVisitAt(@Param("taskId") long taskId);
    List<com.armada.hyperlink.task.model.vo.HyperlinkVisitBucketRow> selectVisitUvBuckets(
            @Param("taskId") long taskId, @Param("anchorAt") long anchorAt,
            @Param("endAt") long endAt, @Param("bucketMs") long bucketMs);
    @InterceptorIgnore(tenantLine = "true")
    List<HyperlinkTaskRecipient> selectAttributionRetentionCandidates(
            @Param("cutoffAt") long cutoffAt, @Param("limit") int limit);
    @InterceptorIgnore(tenantLine = "true")
    int purgeAttribution(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("cutoffAt") long cutoffAt, @Param("purgedAt") long purgedAt);
    /** 无锁读取一个索引有界的待投影候选批次。 */
    @InterceptorIgnore(tenantLine = "true")
    List<HyperlinkTaskRecipient> selectMetricsProjectionCandidates(@Param("limit") int limit);
    /** 按上一步候选主键精确领取仍待投影的 recipient。 */
    @InterceptorIgnore(tenantLine = "true")
    List<HyperlinkTaskRecipient> lockMetricsProjectionBatch(
            @Param("recipientIds") List<Long> recipientIds);
    int insertIgnoreBatch(@Param("rows") List<HyperlinkTaskRecipient> rows);
    int countByTaskId(@Param("taskId") long taskId);
    int countCommanded(@Param("taskId") long taskId);
    int countSendingByTaskId(@Param("taskId") long taskId);
    int countSendingByRoundId(@Param("roundId") long roundId);
    /**
     * 当前读锁定同账号最多 limit 个发送中事实，供跨任务硬容量门禁计数。
     */
    @InterceptorIgnore(tenantLine = "true")
    List<Long> lockSendingIdsByAccount(
            @Param("tenantId") long tenantId,
            @Param("accountId") long accountId,
            @Param("limit") int limit);
    int countPendingUnassigned(@Param("taskId") long taskId);
    int countUnsettledByTaskId(@Param("taskId") long taskId);
    /** 汇总任务冻结 recipient 的国家人数，用于失败后原事实重新报价。 */
    List<HyperlinkRecipientCountryCount> selectCountryCounts(@Param("taskId") long taskId);
    List<HyperlinkRecipientCountryCount> selectSentCountryCounts(@Param("taskId") long taskId);
    @InterceptorIgnore(tenantLine = "true")
    List<HyperlinkTaskRecipient> lockUnsubmittedForStop(@Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("dataPackageId") long dataPackageId,
            @Param("generation") int generation, @Param("limit") int limit);
    int deleteUnsubmitted(@Param("taskId") long taskId, @Param("limit") int limit);
    HyperlinkTaskRecipient selectByCommandId(@Param("commandId") String commandId);
    /** 按事件携带的任务/recipient 身份读取当前逻辑发送，用于识别旧尝试的迟到回调。 */
    @InterceptorIgnore(tenantLine = "true")
    HyperlinkTaskRecipient selectCurrentByIdentity(
            @Param("tenantId") long tenantId,
            @Param("taskId") long taskId,
            @Param("recipientId") long recipientId);
    HyperlinkTaskRecipient selectByProtocolMessage(@Param("accountId") long accountId,
            @Param("protocolId") String protocolId, @Param("messageId") String messageId);
    /** 按 ACK 完整关联身份锁定 recipient，供 usage 后固定锁序重读最新状态。 */
    @InterceptorIgnore(tenantLine = "true")
    HyperlinkTaskRecipient selectByIdentityForUpdate(@Param("tenantId") long tenantId,
            @Param("taskId") long taskId, @Param("recipientId") long recipientId,
            @Param("commandId") String commandId);
    @InterceptorIgnore(tenantLine = "true")
    HyperlinkTaskRecipient lockPending(@Param("tenantId") long tenantId,
            @Param("taskId") long taskId, @Param("roundId") long roundId,
            @Param("now") long now);
    int assignCommand(HyperlinkTaskRecipient entity);
    /** 记录协议接受时间；若极短窗口内已投影 SENDING，则重开一次发送量差分。 */
    int markSubmitted(@Param("commandId") String commandId,
            @Param("submittedAt") long submittedAt,
            @Param("nextReconciliationAt") long nextReconciliationAt);
    int applyResult(HyperlinkTaskRecipient entity);
    /** 明确账号受限且未发送成功时，把同一料子原子释放回待发并递增尝试号。 */
    int requeueAfterAccountRestriction(
            @Param("id") long id,
            @Param("commandId") String commandId,
            @Param("now") long now);
    int scheduleReconciliation(@Param("commandId") String commandId,
            @Param("nextDispatchAt") long nextDispatchAt, @Param("now") long now);
    int advanceAck(@Param("entity") HyperlinkTaskRecipient entity,
            @Param("fromStatus") int fromStatus);
    int stopUnsubmittedByIds(@Param("taskId") long taskId,
            @Param("recipientIds") List<Long> recipientIds, @Param("now") long now);
    int markProjected(@Param("taskId") long taskId, @Param("now") long now);
    /** 仅确认当前事务已经锁定并处理的 recipient。 */
    @InterceptorIgnore(tenantLine = "true")
    int markProjectionBatch(@Param("recipientIds") List<Long> recipientIds,
            @Param("now") long now);
    int failUnassignedBatch(@Param("taskId") long taskId,
            @Param("failCode") String failCode, @Param("failReason") String failReason,
            @Param("now") long now, @Param("limit") int limit);
    @InterceptorIgnore(tenantLine = "true")
    List<HyperlinkReconciliationCandidate> selectReconciliationCandidates(
            @Param("now") long now, @Param("limit") int limit);
}
