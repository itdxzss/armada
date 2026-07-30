package com.armada.group.model.vo;

import com.armada.group.model.enums.HistoricalGroupAddStatus;
import com.armada.group.model.enums.HistoricalGroupContactStatus;
import com.armada.group.model.enums.HistoricalGroupMarketingStatus;
import com.armada.group.model.enums.HistoricalGroupMaterialType;
import com.armada.group.model.enums.HistoricalGroupMemberSendStatus;
import com.armada.group.model.enums.HistoricalGroupPullStatus;
import java.util.List;

/**
 * 历史群单群拉人执行详情。
 *
 * @param id                    执行 ID
 * @param idempotencyKey        创建幂等键
 * @param operationAccountId    后端自动选择的实际执行账号 ID
 * @param sourceAccountGroupId  来源历史群账号组 ID
 * @param groupJid              目标群 JID
 * @param groupSubject          创建时实时群名快照
 * @param inviteUrl             创建时服务端实时邀请链接
 * @param pullerAccountGroupId  拉手账号分组 ID
 * @param pullerAccountId       实际选中的拉手账号 ID
 * @param pullerPhone           实际拉手完整 WhatsApp 号码
 * @param pullerParticipantJid  实际拉手完整 WhatsApp 成员 JID
 * @param singleAddCount        单次添加合计人数
 * @param marketingTemplateId   营销阶段选择的模板 ID
 * @param normalCount           普通成员数
 * @param marketingCount        营销成员数
 * @param invalidCount          无效输入行数
 * @param duplicateCount        重复有效号码行数
 * @param pullSuccessCount      拉人成功数
 * @param pullFailureCount      拉人失败数
 * @param sendSuccessCount      营销发送成功数
 * @param sendFailureCount      营销发送失败数
 * @param pullStatus            拉人状态
 * @param marketingStatus       营销状态
 * @param failureStage          失败阶段
 * @param errorCode             执行错误码
 * @param errorMessage          完整执行错误
 * @param startedAt             开始时间
 * @param finishedAt            完成时间
 * @param createdAt             创建时间
 * @param updatedAt             更新时间
 * @param members               营销优先的逐成员结果
 */
public record HistoricalGroupPullExecutionVO(
        Long id,
        String idempotencyKey,
        Long operationAccountId,
        Long sourceAccountGroupId,
        String groupJid,
        String groupSubject,
        String inviteUrl,
        Long pullerAccountGroupId,
        Long pullerAccountId,
        String pullerPhone,
        String pullerParticipantJid,
        Integer singleAddCount,
        Long marketingTemplateId,
        Integer normalCount,
        Integer marketingCount,
        Integer invalidCount,
        Integer duplicateCount,
        Integer pullSuccessCount,
        Integer pullFailureCount,
        Integer sendSuccessCount,
        Integer sendFailureCount,
        HistoricalGroupPullStatus pullStatus,
        HistoricalGroupMarketingStatus marketingStatus,
        String failureStage,
        String errorCode,
        String errorMessage,
        Long startedAt,
        Long finishedAt,
        Long createdAt,
        Long updatedAt,
        List<MemberVO> members) {

    /**
     * 历史群拉人逐成员结果。
     *
     * @param id                  成员明细 ID
     * @param lineNo              首次有效源行号
     * @param phone               归一化完整号码
     * @param participantJid      归一化 WhatsApp 成员 JID
     * @param materialType        料子类型
     * @param accountId           匹配到的 Armada 营销账号 ID
     * @param protocolAccountId   匹配到的协议账号 ID 快照
     * @param contactStatus       联系人预存状态
     * @param contactErrorCode    联系人错误码
     * @param contactErrorMessage 完整联系人错误
     * @param addStatus           拉人状态
     * @param addErrorCode        拉人错误码
     * @param addErrorMessage     完整拉人错误
     * @param sendStatus          营销发送状态
     * @param sendCommandId       唯一发送命令 ID
     * @param sendResultEventId   首个发送结果事件 ID
     * @param sendErrorCode       发送错误码
     * @param sendErrorMessage    完整发送错误
     */
    public record MemberVO(
            Long id,
            Integer lineNo,
            String phone,
            String participantJid,
            HistoricalGroupMaterialType materialType,
            Long accountId,
            String protocolAccountId,
            HistoricalGroupContactStatus contactStatus,
            String contactErrorCode,
            String contactErrorMessage,
            HistoricalGroupAddStatus addStatus,
            String addErrorCode,
            String addErrorMessage,
            HistoricalGroupMemberSendStatus sendStatus,
            String sendCommandId,
            String sendResultEventId,
            String sendErrorCode,
            String sendErrorMessage) {
    }
}
