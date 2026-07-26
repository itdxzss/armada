package com.armada.marketing.controller;

import com.armada.marketing.model.dto.BatchIdsRequest;
import com.armada.marketing.model.dto.CreateMarketingTaskDTO;
import com.armada.marketing.model.dto.MarketingTaskQuery;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.vo.MarketingAccountTreeVO;
import com.armada.marketing.model.vo.MarketingTaskDetailVO;
import com.armada.marketing.model.vo.MarketingTaskVO;
import com.armada.marketing.model.vo.MarketingTemplateVO;
import com.armada.marketing.model.vo.MarketingTreeAccountVO;
import com.armada.marketing.service.MarketingTaskService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 群组营销任务第一阶段接口。
 *
 * <p>开放任务列表、创建、详情、账号群树、启动、暂停、继续、手动关闭、批量删除和修改营销素材。</p>
 */
@RestController
@RequestMapping("/api/marketing-tasks")
@PreAuthorize("hasAuthority('tenant:marketing_task:view')")
public class MarketingTaskController {

    private final MarketingTaskService service;

    /**
     * 注入营销任务 Service。
     *
     * @param service 群组营销任务业务服务
     */
    public MarketingTaskController(MarketingTaskService service) {
        this.service = service;
    }

    /**
     * 查询营销任务列表。
     *
     * <p>支持 ID 精准筛选、任务名称模糊筛选、状态筛选和最后发送时间范围筛选;分页参数沿用
     * `page/pageSize`。列表只返回任务主信息,不返回 target 明细;模板展示字段按当前页任务引用的
     * 去重模板 ID 批量读取,不依赖模板列表接口。模板已软删除或跨租户不可见时任务仍正常返回,
     * 对应模板展示字段为 null。</p>
     *
     * @param query 查询和分页参数
     * @return 营销任务分页列表,每行包含 marketingTemplateContent、marketingTemplateBodyText
     * 和 marketingTemplatePromotionLink
     */
    @GetMapping
    public ApiResponse<PageResult<MarketingTaskVO>> list(@ModelAttribute MarketingTaskQuery query) {
        return ApiResponse.ok(service.listTasks(query));
    }

    /**
     * 新建营销任务。
     *
     * <p>请求体包含任务配置和账号维度目标选择。保存成功后立即锁定全部所选账号；是否执行仍以
     * 任务开始、结束时间和调度器推进结果为准。</p>
     *
     * @param request 新建营销任务入参
     * @return 创建后的营销任务
     */
    @PostMapping
    public ApiResponse<MarketingTaskVO> create(@RequestBody CreateMarketingTaskDTO request) {
        return ApiResponse.ok(service.createTask(request));
    }

    /**
     * 查询建任务抽屉的账号树首屏。
     *
     * <p>前端选择账号分组后调用本接口。这里只返回账号分组内在线可用账号,
     * 不调用协议层查群,避免大分组打开抽屉时触发长耗时批量协议请求。</p>
     *
     * @param groupId 账号分组 ID
     * @return 账号→可营销群树
     */
    @GetMapping("/account-tree")
    public ApiResponse<MarketingAccountTreeVO> accountTree(@RequestParam Long groupId) {
        return ApiResponse.ok(service.accountTree(groupId));
    }

    /**
     * 懒加载单个账号的实时可营销群。
     *
     * <p>前端展开某个账号节点时调用。账号来自首屏账号树,因此本接口不再重复校验账号分组归属,
     * 只按账号本身在线、风控、禁言和租户隔离条件读取并实时刷新群列表。</p>
     *
     * @param accountId 账号 ID
     * @return 账号节点及其可营销群
     */
    @GetMapping("/account-tree/accounts/{accountId}/groups")
    public ApiResponse<MarketingTreeAccountVO> accountGroups(@PathVariable Long accountId) {
        return ApiResponse.ok(service.accountGroups(accountId));
    }

    /**
     * 查询营销任务详情。
     *
     * <p>详情包含任务主信息和账号×群组目标明细,供页面右侧明细抽屉展示。</p>
     *
     * @param id 营销任务 ID
     * @return 营销任务详情
     */
    @GetMapping("/{id}")
    public ApiResponse<MarketingTaskDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.getDetail(id));
    }

    /**
     * 启动营销任务。
     *
     * <p>仅允许未启动任务操作。未到计划开始时间时继续等待，由调度器到点自动执行。</p>
     *
     * @param id 营销任务 ID
     * @return 启动后的营销任务
     */
    @PostMapping("/{id}/start")
    public ApiResponse<MarketingTaskVO> start(@PathVariable Long id) {
        return ApiResponse.ok(service.startTask(id));
    }

    /**
     * 暂停执行中的营销任务。
     *
     * @param id 营销任务 ID
     * @return 暂停后的营销任务
     */
    @PostMapping("/{id}/pause")
    public ApiResponse<MarketingTaskVO> pause(@PathVariable Long id) {
        return ApiResponse.ok(service.pauseTask(id));
    }

    /**
     * 继续已暂停的营销任务。
     *
     * @param id 营销任务 ID
     * @return 继续后的营销任务
     */
    @PostMapping("/{id}/resume")
    public ApiResponse<MarketingTaskVO> resume(@PathVariable Long id) {
        return ApiResponse.ok(service.resumeTask(id));
    }

    /**
     * 手动关闭非终态营销任务。
     *
     * @param id 营销任务 ID
     * @return 已关闭的营销任务
     */
    @PostMapping("/{id}/close")
    public ApiResponse<MarketingTaskVO> close(@PathVariable Long id) {
        return ApiResponse.ok(service.closeTask(id));
    }

    /**
     * 通过任务修改其引用的营销模板。
     *
     * <p>任务不复制营销素材正文。这里会定位任务当前引用的共享营销模板,并复用营销模板服务的
     * 编辑校验和更新逻辑。</p>
     *
     * @param id      营销任务 ID
     * @param request 新的营销模板配置
     * @return 更新后的营销模板
     */
    @PutMapping("/{id}/marketing-template")
    public ApiResponse<MarketingTemplateVO> updateMarketingTemplate(@PathVariable Long id,
                                                                    @RequestBody MarketingTemplateDTO request) {
        return ApiResponse.ok(service.updateMarketingTemplate(id, request));
    }

    /**
     * 批量软删营销任务。
     *
     * <p>只允许删除已完成或已关闭任务；非终态任务必须先手动关闭。</p>
     *
     * @param request 任务 ID 列表
     * @return 实际软删行数
     */
    @PostMapping("/batch-delete")
    public ApiResponse<Integer> batchDelete(@RequestBody BatchIdsRequest request) {
        return ApiResponse.ok(service.batchDelete(request.ids()));
    }
}
