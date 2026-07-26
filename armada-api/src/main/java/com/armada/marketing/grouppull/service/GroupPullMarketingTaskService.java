package com.armada.marketing.grouppull.service;

import com.armada.marketing.grouppull.model.dto.CreateGroupPullMarketingTaskDTO;
import com.armada.marketing.grouppull.model.dto.GroupPullMarketingGroupQuery;
import com.armada.marketing.grouppull.model.dto.GroupPullMarketingTaskQuery;
import com.armada.marketing.grouppull.model.vo.GroupPullMarketingGroupVO;
import com.armada.marketing.grouppull.model.vo.GroupPullMarketingTaskDetailVO;
import com.armada.marketing.grouppull.model.vo.GroupPullMarketingTaskVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * 拉群营销任务配置、查询和生命周期业务入口。
 */
public interface GroupPullMarketingTaskService {

    /**
     * 保存待启动任务和本次唯一上传的料子文件。
     *
     * @param request 拉群营销任务配置
     * @param materialFile 本任务唯一 TXT 或 CSV 料子文件
     * @return 创建后的任务配置及汇总详情
     * @throws BusinessException 当配置、账号分组、模板或料子文件不符合要求时抛出
     */
    GroupPullMarketingTaskDetailVO create(CreateGroupPullMarketingTaskDTO request, MultipartFile materialFile);

    /**
     * 分页查询拉群营销一级任务列表。
     *
     * @param query 查询条件和分页参数
     * @return 当前页任务汇总及总数
     */
    PageResult<GroupPullMarketingTaskVO> list(GroupPullMarketingTaskQuery query);

    /**
     * 查询单条任务配置与汇总。
     *
     * @param id 统一营销任务 ID
     * @return 任务配置及汇总详情
     * @throws BusinessException 当任务不存在时抛出
     */
    GroupPullMarketingTaskDetailVO detail(Long id);

    /**
     * 分页查询任务正式进入建群流程后的群组明细。
     *
     * @param taskId 统一营销任务 ID
     * @param query 分页参数
     * @return 按执行 ID 升序排列的群组明细及总数
     * @throws BusinessException 当拉群营销任务不存在时抛出
     */
    PageResult<GroupPullMarketingGroupVO> groups(Long taskId, GroupPullMarketingGroupQuery query);

    /**
     * 正式启动待启动任务并原子锁定整个营销分组。
     *
     * @param id 统一营销任务 ID
     * @return 启动后的任务详情
     * @throws BusinessException 当状态、账号资源或分组占用不允许启动时抛出
     */
    GroupPullMarketingTaskDetailVO start(Long id);

    /**
     * 暂停执行中的任务并继续持有资源。
     *
     * @param id 统一营销任务 ID
     * @return 暂停后的任务详情
     * @throws BusinessException 当任务不是执行中状态时抛出
     */
    GroupPullMarketingTaskDetailVO pause(Long id);

    /**
     * 恢复资源锁仍有效的已暂停任务。
     *
     * @param id 统一营销任务 ID
     * @return 恢复后的任务详情
     * @throws BusinessException 当任务未暂停或资源锁已经失效时抛出
     */
    GroupPullMarketingTaskDetailVO resume(Long id);

    /**
     * 人工结束任务并进入安全释放中。
     *
     * @param id 统一营销任务 ID
     * @return 发起释放后的任务详情
     * @throws BusinessException 当任务状态不允许释放时抛出
     */
    GroupPullMarketingTaskDetailVO release(Long id);

    /**
     * 删除尚未启动且未持有资源的任务。
     *
     * @param id 统一营销任务 ID
     * @throws BusinessException 当任务不存在或已经启动时抛出
     */
    void delete(Long id);
}
