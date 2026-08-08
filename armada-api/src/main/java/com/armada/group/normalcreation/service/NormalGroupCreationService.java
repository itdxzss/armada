package com.armada.group.normalcreation.service;

import com.armada.group.normalcreation.model.dto.NormalGroupCreationCreateDTO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationTaskDetailVO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationTaskVO;

/** 新建普群任务业务服务。 */
public interface NormalGroupCreationService {

    /** 校验并冻结资源，创建异步任务。 */
    NormalGroupCreationTaskVO create(
            String idempotencyKey, NormalGroupCreationCreateDTO request, long userId);

    /** 查询任务及其计划群明细。 */
    NormalGroupCreationTaskDetailVO detail(long taskId);

    /** 重新发布一个明确失败的计划群当前阶段；结果未知的建群不得重试。 */
    void retry(long taskId, long itemId, long userId);
}
