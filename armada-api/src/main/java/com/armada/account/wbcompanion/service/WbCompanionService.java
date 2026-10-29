package com.armada.account.wbcompanion.service;

import com.armada.account.wbcompanion.model.dto.WbCompanionCreateDTO;
import com.armada.account.wbcompanion.model.vo.WbCompanionVOs;
import java.util.List;

/** 账号页"登录到 WB 分身"：选目标环境和会员、发起任务、看进度、看详情、解绑。 */
public interface WbCompanionService {

    /**
     * 本环境配置的可导入 WB 环境。
     *
     * @return 目标列表；未配置时为空
     */
    List<WbCompanionVOs.WbTargetVO> targets();

    /**
     * 在目标 WB 按用户名精确查会员，并带回目标环境是否开放影子。
     *
     * @param wbEnvId WB 环境标识
     * @param username 用户名
     * @return 会员；不存在时抛 NOT_FOUND
     */
    WbCompanionVOs.WbMemberVO member(String wbEnvId, String username);

    /**
     * 为一批安卓号各建一个登录任务；不符合条件的账号逐个返回原因，不影响其他账号。
     *
     * @param dto 发起参数
     * @param userId 发起人
     * @return 每个账号的结果
     */
    WbCompanionVOs.WbCompanionCreateResultVO create(WbCompanionCreateDTO dto, Long userId);

    /**
     * 任务进度。
     *
     * @param taskId 任务 ID
     * @return 任务进度
     */
    WbCompanionVOs.WbCompanionTaskVO task(Long taskId);

    /**
     * 停止进行中任务的剩余连接；已挂上的连接保留。
     *
     * @param taskId 任务 ID
     */
    void stop(Long taskId);

    /**
     * 账号列表"WB 分身"列，读本地快照。
     *
     * @param accountIds 当前页账号 ID，最多 500 个
     * @return 有绑定或有进行中任务的账号
     */
    List<WbCompanionVOs.WbCompanionSummaryVO> summaries(List<Long> accountIds);

    /**
     * 账号的 WB 分身详情，实时查询 WB 并刷新快照；WB 不可达时 live=false。
     *
     * @param accountId 账号 ID
     * @return 详情
     */
    WbCompanionVOs.WbCompanionDetailVO detail(Long accountId);

    /**
     * 全部解绑：让 WB 删除这台设备和它的影子，再删除本地绑定。
     *
     * @param accountId 账号 ID
     */
    void unbind(Long accountId);
}
