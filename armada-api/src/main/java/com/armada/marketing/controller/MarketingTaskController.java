package com.armada.marketing.controller;

import com.armada.marketing.model.dto.CreateMarketingTaskDTO;
import com.armada.marketing.model.dto.MarketingTaskQuery;
import com.armada.marketing.model.vo.MarketingTaskDetailVO;
import com.armada.marketing.model.vo.MarketingTaskVO;
import com.armada.marketing.service.MarketingTaskService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 群组营销任务第一阶段接口。
 *
 * <p>本 Controller 只开放任务列表、创建和详情读取。启动/停止、批量删除、账号群树和修改营销素材
 * 按后续 checkpoint 逐步补齐。</p>
 */
@RestController
@RequestMapping("/api/marketing-tasks")
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
     * `page/pageSize`。列表只返回任务主信息,不返回 target 明细。</p>
     *
     * @param query 查询和分页参数
     * @return 营销任务分页列表
     */
    @GetMapping
    public ApiResponse<PageResult<MarketingTaskVO>> list(@ModelAttribute MarketingTaskQuery query) {
        return ApiResponse.ok(service.listTasks(query));
    }

    /**
     * 新建营销任务。
     *
     * <p>请求体包含任务配置和账号→群组选择。保存成功后返回任务主信息;若入参选择立即启动,
     * 当前阶段只把任务状态置为发送中,不触发真实发送。</p>
     *
     * @param request 新建营销任务入参
     * @return 创建后的营销任务
     */
    @PostMapping
    public ApiResponse<MarketingTaskVO> create(@RequestBody CreateMarketingTaskDTO request) {
        return ApiResponse.ok(service.createTask(request));
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
}
