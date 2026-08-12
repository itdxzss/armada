package com.armada.group.service;

import com.armada.group.model.dto.GroupBatchSubmitDTO;
import com.armada.group.model.vo.GroupBatchTaskAcceptedVO;
import com.armada.group.model.vo.GroupBatchTaskDetailVO;

/** 群组列表批量刷新任务的提交与进度查询。 */
public interface GroupBatchTaskService {

    /**
     * 提交批量刷新群链接任务。
     *
     * <p>逐项校验群存在、租户可见与封禁状态;封禁群不执行写路径,直接以失败明细落库并计入总数,
     * 保证越过前端置灰的请求也不会改动数据。</p>
     *
     * @param dto 已勾选群 ID 与前端幂等键
     * @param operatorId 当前登录用户 ID
     * @return 任务受理结果;命中幂等键时返回已有任务
     * @throws com.armada.shared.exception.BusinessException 未勾选、幂等键缺失或无任何合法群时抛出
     */
    GroupBatchTaskAcceptedVO submitRefreshLinks(GroupBatchSubmitDTO dto, long operatorId);

    /**
     * 提交批量获取最新群信息任务。
     *
     * <p>只读同步,封禁群同样允许执行;逐项冻结提交时的群详情同步成功时间作为完成判定基线。</p>
     *
     * @param dto 已勾选群 ID 与前端幂等键
     * @param operatorId 当前登录用户 ID
     * @return 任务受理结果;命中幂等键时返回已有任务
     * @throws com.armada.shared.exception.BusinessException 未勾选、幂等键缺失或无任何合法群时抛出
     */
    GroupBatchTaskAcceptedVO submitRefreshInfo(GroupBatchSubmitDTO dto, long operatorId);

    /**
     * 查询批量任务进度与逐项结果。
     *
     * @param taskId 批量任务 ID
     * @return 汇总与明细
     * @throws com.armada.shared.exception.BusinessException 任务不存在或不属于当前租户时抛出
     */
    GroupBatchTaskDetailVO detail(Long taskId);

    /**
     * 取消批量任务中尚未开始执行的明细。
     *
     * <p>前端关闭任务弹窗即调用:关闭后 taskId 被丢弃、明细再也不会展示(PRD P-06),
     * 继续把剩余上千个群跑完只是白花协议流量。已终结的明细与已终结的任务都不受影响,
     * 重复调用是幂等的空操作。</p>
     *
     * @param taskId 批量任务 ID
     * @return 实际取消的明细数
     * @throws com.armada.shared.exception.BusinessException 任务不存在或不属于当前租户时抛出
     */
    int cancel(Long taskId);
}
