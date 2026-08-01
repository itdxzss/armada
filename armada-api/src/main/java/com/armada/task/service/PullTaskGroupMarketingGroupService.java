package com.armada.task.service;

import com.armada.shared.response.PageResult;
import com.armada.task.model.dto.PullTaskGroupMarketingCandidateQuery;
import com.armada.task.model.dto.PullTaskGroupMarketingWaitingPoolAddDTO;
import com.armada.task.model.dto.PullTaskGroupMarketingWaitingPoolRemoveDTO;
import com.armada.task.model.vo.PullTaskGroupMarketingCandidateVO;
import com.armada.task.model.vo.PullTaskGroupMarketingWaitingPoolVO;

/** 拉群营销候选群组和创建前等待池服务。 */
public interface PullTaskGroupMarketingGroupService {

    /**
     * 按 JID 去重并分页读取当前租户候选群组。
     *
     * @param query 筛选与分页
     * @param operatorId 当前用户
     * @return 候选群组
     */
    PageResult<PullTaskGroupMarketingCandidateVO> listCandidates(
            PullTaskGroupMarketingCandidateQuery query,
            long operatorId);

    /**
     * 重新校验群组并尝试加入当前用户等待池。
     *
     * @param request 加入请求
     * @param operatorId 当前用户
     * @return 最新等待池和逐群拒绝原因
     */
    PullTaskGroupMarketingWaitingPoolVO addWaiting(
            PullTaskGroupMarketingWaitingPoolAddDTO request,
            long operatorId);

    /**
     * 读取并续租当前用户等待池。
     *
     * @param reservationToken 等待池标识
     * @param operatorId 当前用户
     * @return 等待池
     */
    PullTaskGroupMarketingWaitingPoolVO getWaiting(String reservationToken, long operatorId);

    /**
     * 移出并释放等待池中的单个群组。
     *
     * @param request 移出请求
     * @param operatorId 当前用户
     * @return 最新等待池
     */
    PullTaskGroupMarketingWaitingPoolVO removeWaiting(
            PullTaskGroupMarketingWaitingPoolRemoveDTO request,
            long operatorId);

    /**
     * 取消创建时释放当前用户的整个等待池；等待池已过期时保持幂等。
     *
     * @param reservationToken 等待池标识
     * @param operatorId 当前用户
     */
    void releaseWaiting(String reservationToken, long operatorId);
}
