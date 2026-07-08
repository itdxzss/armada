package com.armada.marketing.service;

import com.armada.marketing.model.dto.CreateGroupCreationMarketingTaskDTO;
import com.armada.marketing.model.dto.GroupCreationMarketingTaskQuery;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.armada.marketing.model.vo.GroupCreationMarketingExportFile;
import com.armada.marketing.model.vo.GroupCreationMarketingTaskDetailVO;
import com.armada.marketing.model.vo.GroupCreationMarketingTaskVO;
import com.armada.shared.response.PageResult;
import java.util.List;

/**
 * 建群营销任务业务服务。
 *
 * <p>封装建群营销任务创建、列表详情、账号候选、停止和导出逻辑。Controller 只调用本接口,
 * 不直接访问 Mapper 或协议层。</p>
 */
public interface GroupCreationMarketingTaskService {

    /**
     * 创建建群营销任务并生成待处理执行项。
     *
     * @param request 创建任务请求
     * @return 创建后的任务详情
     */
    GroupCreationMarketingTaskDetailVO createTask(CreateGroupCreationMarketingTaskDTO request);

    /**
     * 分页查询建群营销任务。
     *
     * @param query 查询条件和分页参数
     * @return 建群营销任务分页列表
     */
    PageResult<GroupCreationMarketingTaskVO> listTasks(GroupCreationMarketingTaskQuery query);

    /**
     * 查询建群营销任务详情。
     *
     * @param id 任务 ID
     * @return 任务详情
     */
    GroupCreationMarketingTaskDetailVO getDetail(Long id);

    /**
     * 查询账号分组内可执行建群营销的账号候选。
     *
     * @param accountGroupId 账号分组 ID
     * @return 候选账号列表
     */
    List<GroupCreationMarketingAccountCandidate> accountCandidates(Long accountGroupId);

    /**
     * 停止建群营销任务并放弃未终态执行项。
     *
     * @param id 任务 ID
     * @return 实际更新的任务行数
     */
    int stopTask(Long id);

    /**
     * 导出指定建群营销任务的统计 Excel 文件。
     *
     * @param ids 任务 ID 列表
     * @return 导出文件内容和响应元数据
     */
    GroupCreationMarketingExportFile exportTasks(List<Long> ids);
}
