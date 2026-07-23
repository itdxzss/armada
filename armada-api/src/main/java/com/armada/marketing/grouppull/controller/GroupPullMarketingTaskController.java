package com.armada.marketing.grouppull.controller;

import com.armada.marketing.grouppull.model.dto.CreateGroupPullMarketingTaskDTO;
import com.armada.marketing.grouppull.model.dto.GroupPullMarketingTaskQuery;
import com.armada.marketing.grouppull.model.vo.GroupPullMarketingTaskDetailVO;
import com.armada.marketing.grouppull.model.vo.GroupPullMarketingTaskVO;
import com.armada.marketing.grouppull.service.GroupPullMarketingTaskService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 独立“拉群营销”菜单的任务接口。
 *
 * <p>负责任务创建、一级列表、配置详情和生命周期操作的参数接收与响应组装；
 * 配置校验、资源锁定及状态流转全部由 Service 处理。</p>
 */
@RestController
@RequestMapping("/api/group-pull-marketing-tasks")
public class GroupPullMarketingTaskController {

    /** 拉群营销任务业务服务。 */
    private final GroupPullMarketingTaskService service;

    /**
     * 注入拉群营销任务业务服务。
     *
     * @param service 拉群营销任务业务服务
     */
    public GroupPullMarketingTaskController(GroupPullMarketingTaskService service) {
        this.service = service;
    }

    /**
     * 分页查询拉群营销一级任务列表。
     *
     * <p>支持任务 ID、任务名称、主状态、阻塞原因和资源状态组合筛选，列表只返回任务汇总。</p>
     *
     * @param query 查询条件和分页参数
     * @return 当前页拉群营销任务及总数
     */
    @GetMapping
    public ApiResponse<PageResult<GroupPullMarketingTaskVO>> list(
            @ModelAttribute GroupPullMarketingTaskQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /**
     * 保存一条待启动拉群营销任务及其唯一料子文件。
     *
     * <p>请求使用 {@code multipart/form-data}：{@code config} 为配置 JSON，{@code materialFile}
     * 为唯一 TXT 或 CSV 文件；保存阶段不锁定营销分组。</p>
     *
     * @param config 拉群营销任务配置
     * @param materialFile 本任务唯一料子文件
     * @return 创建后的任务配置与汇总详情
     * @throws BusinessException 当配置、账号分组、模板或料子文件不符合要求时抛出
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<GroupPullMarketingTaskDetailVO> create(
            @RequestPart("config") CreateGroupPullMarketingTaskDTO config,
            @RequestPart("materialFile") MultipartFile materialFile) {
        return ApiResponse.ok(service.create(config, materialFile));
    }

    /**
     * 查询单条拉群营销任务配置及聚合统计。
     *
     * @param id 统一营销任务 ID
     * @return 任务配置与汇总详情，不包含料子和群组明细
     * @throws BusinessException 当任务不存在时抛出
     */
    @GetMapping("/{id}")
    public ApiResponse<GroupPullMarketingTaskDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    /**
     * 正式启动待启动任务并原子锁定整个营销分组。
     *
     * @param id 统一营销任务 ID
     * @return 启动后的任务详情
     * @throws BusinessException 当任务状态、账号资源或营销分组锁不允许启动时抛出
     */
    @PostMapping("/{id}/start")
    public ApiResponse<GroupPullMarketingTaskDetailVO> start(@PathVariable Long id) {
        return ApiResponse.ok(service.start(id));
    }

    /**
     * 暂停执行中的拉群营销任务，同时继续保留已有资源占用。
     *
     * @param id 统一营销任务 ID
     * @return 暂停后的任务详情
     * @throws BusinessException 当任务不是执行中状态时抛出
     */
    @PostMapping("/{id}/pause")
    public ApiResponse<GroupPullMarketingTaskDetailVO> pause(@PathVariable Long id) {
        return ApiResponse.ok(service.pause(id));
    }

    /**
     * 恢复资源锁仍有效的已暂停拉群营销任务。
     *
     * @param id 统一营销任务 ID
     * @return 恢复后的任务详情
     * @throws BusinessException 当任务未暂停或营销分组锁已经失效时抛出
     */
    @PostMapping("/{id}/resume")
    public ApiResponse<GroupPullMarketingTaskDetailVO> resume(@PathVariable Long id) {
        return ApiResponse.ok(service.resume(id));
    }

    /**
     * 人工结束执行中或已暂停任务并进入安全释放流程。
     *
     * <p>接口只发起释放并立即返回“已手动结束、释放中”，后台安全收口完成后资源状态变为已释放。</p>
     *
     * @param id 统一营销任务 ID
     * @return 发起释放后的任务详情
     * @throws BusinessException 当任务状态不允许释放时抛出
     */
    @PostMapping("/{id}/release")
    public ApiResponse<GroupPullMarketingTaskDetailVO> release(@PathVariable Long id) {
        return ApiResponse.ok(service.release(id));
    }

    /**
     * 删除从未启动且未持有资源的拉群营销任务。
     *
     * @param id 统一营销任务 ID
     * @return 空成功响应
     * @throws BusinessException 当任务不存在或已经启动时抛出
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
