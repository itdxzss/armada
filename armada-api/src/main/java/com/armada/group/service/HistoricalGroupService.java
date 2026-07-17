package com.armada.group.service;

import com.armada.group.model.dto.HistoricalGroupParticipantActionDTO;
import com.armada.group.model.vo.HistoricalGroupDetailVO;
import com.armada.group.model.vo.HistoricalGroupItemVO;
import com.armada.group.model.vo.HistoricalGroupParticipantActionVO;
import java.util.List;

/**
 * 账号登录前历史群的列表与请求级刷新服务。
 */
public interface HistoricalGroupService {

    /**
     * 读取当前租户操作账号的 baseline 历史群,不调用协议层。
     *
     * @param accountId 操作账号 ID
     * @return 按 baseline JID 顺序排列的未验证历史群
     */
    List<HistoricalGroupItemVO> listHistoricalGroups(Long accountId);

    /**
     * 请求协议层刷新当前成员关系与轻量摘要,结果仅在本次响应中返回。
     *
     * @param accountId 操作账号 ID
     * @return 按 baseline JID 顺序排列的请求级刷新结果
     */
    List<HistoricalGroupItemVO> refreshHistoricalGroups(Long accountId);

    /**
     * 按需读取 baseline 内单个群的完整 metadata、成员和系统邀请链接。
     *
     * @param accountId 固定操作账号 ID
     * @param groupJid  baseline 群 JID
     * @return 单群实时详情与写操作能力状态
     */
    HistoricalGroupDetailVO getHistoricalGroupDetail(Long accountId, String groupJid);

    /**
     * 使用固定操作账号把 baseline 群普通成员批量提升为管理员。
     *
     * @param dto 固定账号、群 JID 与目标成员列表
     * @return 按请求顺序排列的逐成员结果
     */
    HistoricalGroupParticipantActionVO promoteParticipants(HistoricalGroupParticipantActionDTO dto);

    /**
     * 使用固定操作账号把 baseline 群内其他管理员批量降为普通成员。
     *
     * @param dto 固定账号、群 JID 与目标成员列表
     * @return 按请求顺序排列的逐成员结果
     */
    HistoricalGroupParticipantActionVO demoteParticipants(HistoricalGroupParticipantActionDTO dto);

    /**
     * 使用固定操作账号批量移除 baseline 群内可操作成员。
     *
     * @param dto 固定账号、群 JID 与目标成员列表
     * @return 按请求顺序排列的逐成员结果
     */
    HistoricalGroupParticipantActionVO removeParticipants(HistoricalGroupParticipantActionDTO dto);
}
