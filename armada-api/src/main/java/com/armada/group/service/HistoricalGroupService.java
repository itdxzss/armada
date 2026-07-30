package com.armada.group.service;

import com.armada.group.model.dto.HistoricalGroupParticipantActionDTO;
import com.armada.group.model.vo.HistoricalGroupDetailVO;
import com.armada.group.model.vo.HistoricalGroupParticipantActionVO;

/**
 * 账号登录前历史群的列表与请求级刷新服务。
 */
public interface HistoricalGroupService {

    /**
     * 按需读取账号组历史范围内单个群的完整 metadata、成员和系统邀请链接。
     *
     * @param accountGroupId 来源账号组 ID
     * @param groupJid  历史群 JID
     * @return 单群实时详情与写操作能力状态
     */
    HistoricalGroupDetailVO getHistoricalGroupDetail(Long accountGroupId, String groupJid);

    /**
     * 使用后台自动选择的群主或管理员把历史群普通成员批量提升为管理员。
     *
     * @param dto 账号组、群 JID 与目标成员列表
     * @return 按请求顺序排列的逐成员结果
     */
    HistoricalGroupParticipantActionVO promoteParticipants(HistoricalGroupParticipantActionDTO dto);

    /**
     * 使用后台自动选择的群主或管理员把历史群内其他管理员批量降为普通成员。
     *
     * @param dto 账号组、群 JID 与目标成员列表
     * @return 按请求顺序排列的逐成员结果
     */
    HistoricalGroupParticipantActionVO demoteParticipants(HistoricalGroupParticipantActionDTO dto);

    /**
     * 使用后台自动选择的群主或管理员批量移除历史群内可操作成员。
     *
     * @param dto 账号组、群 JID 与目标成员列表
     * @return 按请求顺序排列的逐成员结果
     */
    HistoricalGroupParticipantActionVO removeParticipants(HistoricalGroupParticipantActionDTO dto);
}
