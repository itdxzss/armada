package com.armada.task.model.vo;

import com.armada.task.model.enums.PullTaskGroupCandidateStatus;
import com.armada.task.model.enums.PullTaskGroupSource;
import java.util.List;

/**
 * 拉群营销候选群组展示与选择事实。
 *
 * @param groupLinkId 群链接主键
 * @param ownerUserId 群入口归属用户 ID；管理员全量视图用于区分归属
 * @param groupJid WhatsApp 群唯一 JID
 * @param groupName 当前群名称
 * @param source 历史群或自收群来源
 * @param ownerPhone 群主手机号
 * @param countryIso2 群主手机号对应国家二字码
 * @param countryName 国家中文名
 * @param countryFlag 国家旗帜
 * @param groupCreatedAt WhatsApp 群创建时间(Unix 秒)
 * @param memberSize 当前群人数
 * @param announceOnly 是否仅管理员可发言
 * @param avatarUrl 群头像地址
 * @param lastSyncedAt 群资料、健康或关系最近同步时间
 * @param sourceJoinTaskId 自收群来源任务 ID
 * @param sourceJoinTaskName 自收群来源任务名称
 * @param sourceJoinedAt 来源账号进群时间
 * @param sourcePromotedAt 来源账号成为管理员时间
 * @param operableAccounts 当前全部可操作管理账号
 * @param eligibleAccountCount 当前可用管理账号数量
 * @param onlineAccountCount 当前在线管理账号数量
 * @param status 当前候选状态
 * @param selectable 当前能否加入等待池
 * @param inCurrentWaitingPool 是否已在本创建页等待池
 * @param occupiedTaskName 当前占用任务名称
 * @param disabledReason 不可选择或等待上线原因
 * @param lastValidatedAt 最近占用校验时间
 */
public record PullTaskGroupMarketingCandidateVO(
        Long groupLinkId,
        Long ownerUserId,
        String groupJid,
        String groupName,
        PullTaskGroupSource source,
        String ownerPhone,
        String countryIso2,
        String countryName,
        String countryFlag,
        Long groupCreatedAt,
        Integer memberSize,
        Boolean announceOnly,
        String avatarUrl,
        Long lastSyncedAt,
        Long sourceJoinTaskId,
        String sourceJoinTaskName,
        Long sourceJoinedAt,
        Long sourcePromotedAt,
        List<PullTaskGroupMarketingCandidateAccountVO> operableAccounts,
        int eligibleAccountCount,
        int onlineAccountCount,
        PullTaskGroupCandidateStatus status,
        boolean selectable,
        boolean inCurrentWaitingPool,
        String occupiedTaskName,
        String disabledReason,
        Long lastValidatedAt) {

    public PullTaskGroupMarketingCandidateVO {
        operableAccounts = operableAccounts == null ? List.of() : List.copyOf(operableAccounts);
    }
}
