package com.armada.marketing.export.service;

import com.armada.marketing.export.model.dto.MarketingTaskExportRequestDTO;
import com.armada.marketing.export.model.vo.MarketingTaskExportFile;
import com.armada.marketing.export.model.vo.MarketingTaskExportJobVO;
import com.armada.shared.security.AuthPrincipal;

/** 普通营销任务异步导出服务。 */
public interface MarketingTaskExportService {

    /**
     * 创建普通营销任务异步导出作业，服务端固定数据快照时间并复用相同活动请求。
     *
     * @param request 导出模式、任务 ID 和可选国家范围
     * @param principal 当前认证用户及租户身份
     * @return 创建或复用后的导出作业状态
     * @throws com.armada.shared.exception.BusinessException 请求非法、任务不可见或并发创建冲突时抛出
     */
    MarketingTaskExportJobVO createJob(MarketingTaskExportRequestDTO request, AuthPrincipal principal);

    /**
     * 查询当前用户创建的单个导出作业。
     *
     * @param id 导出作业 ID
     * @param principal 当前认证用户及租户身份
     * @return 当前作业状态
     * @throws com.armada.shared.exception.BusinessException 作业不存在或不属于当前用户时抛出
     */
    MarketingTaskExportJobVO getJob(Long id, AuthPrincipal principal);

    /**
     * 获取当前用户已成功且未过期导出文件的服务端描述。
     *
     * @param id 导出作业 ID
     * @param principal 当前认证用户及租户身份
     * @return 仅包含服务端受控路径、文件名、类型和大小的文件描述
     * @throws com.armada.shared.exception.BusinessException 作业未成功、已过期或文件缺失时抛出
     */
    MarketingTaskExportFile getDownload(Long id, AuthPrincipal principal);

    /**
     * 领取并处理待执行或租约已过期的导出作业。
     *
     * @param limit 单次最多领取的作业数，服务内部会限制为 1 至 10
     */
    void processPendingJobs(int limit);
}
