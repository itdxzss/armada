package com.armada.marketing.controller;

import com.armada.marketing.model.dto.CreateGroupCreationMarketingTaskDTO;
import com.armada.marketing.model.dto.GroupCreationMarketingTaskExportRequest;
import com.armada.marketing.model.dto.GroupCreationMarketingTaskQuery;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.armada.marketing.model.vo.GroupCreationMarketingExportFile;
import com.armada.marketing.model.vo.GroupCreationMarketingTaskDetailVO;
import com.armada.marketing.model.vo.GroupCreationMarketingTaskVO;
import com.armada.marketing.service.GroupCreationMarketingTaskService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 建群营销任务接口。
 *
 * <p>负责列表、候选账号、创建、停止、详情和导出接口的参数接收与响应组装,
 * 业务校验、任务落库和 Excel 内容生成均交由 Service 层处理。</p>
 */
@RestController
@RequestMapping("/api/group-creation-marketing-tasks")
public class GroupCreationMarketingTaskController {

    /** 建群营销任务业务服务。 */
    private final GroupCreationMarketingTaskService service;

    /**
     * 注入建群营销任务 Service。
     *
     * @param service 建群营销任务业务服务
     */
    public GroupCreationMarketingTaskController(GroupCreationMarketingTaskService service) {
        this.service = service;
    }

    /**
     * 查询建群营销任务分页列表。
     *
     * <p>支持任务 ID、任务名称和状态筛选;列表仅返回任务汇总信息,不返回执行项明细。</p>
     *
     * @param query 查询条件和分页参数
     * @return 建群营销任务分页列表
     */
    @GetMapping
    public ApiResponse<PageResult<GroupCreationMarketingTaskVO>> list(@ModelAttribute GroupCreationMarketingTaskQuery query) {
        return ApiResponse.ok(service.listTasks(query));
    }

    /**
     * 查询账号分组内可用于建群营销的候选账号。
     *
     * <p>用于创建任务前预览账号池。Service 会过滤离线、风控、禁言和缺少协议账号 ID 的账号。</p>
     *
     * @param accountGroupId 账号分组 ID
     * @return 可执行建群营销的账号候选列表
     */
    @GetMapping("/account-candidates")
    public ApiResponse<List<GroupCreationMarketingAccountCandidate>> accountCandidates(@RequestParam Long accountGroupId) {
        return ApiResponse.ok(service.accountCandidates(accountGroupId));
    }

    /**
     * 创建建群营销任务。
     *
     * <p>请求体包含账号分组、营销模板、料子文件和任务配置。创建成功后按账号与料子一一匹配生成待处理执行项。</p>
     *
     * @param request 创建任务请求
     * @return 创建后的任务详情
     */
    @PostMapping
    public ApiResponse<GroupCreationMarketingTaskDetailVO> create(@RequestBody CreateGroupCreationMarketingTaskDTO request) {
        return ApiResponse.ok(service.createTask(request));
    }

    /**
     * 停止建群营销任务。
     *
     * <p>停止会把待处理、建群中和营销发送中的执行项标记为放弃,并同步停止关联的普通营销任务。</p>
     *
     * @param id 建群营销任务 ID
     * @return 实际更新的任务主表行数
     */
    @PostMapping("/{id}/stop")
    public ApiResponse<Integer> stop(@PathVariable Long id) {
        return ApiResponse.ok(service.stopTask(id));
    }

    /**
     * 导出建群营销统计 Excel。
     *
     * <p>导出内容包含任务 ID、群名称、建群人数和进群人数。响应直接返回 XLSX 二进制文件。</p>
     *
     * @param request 导出任务 ID 列表
     * @return Excel 文件二进制响应
     */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody GroupCreationMarketingTaskExportRequest request) {
        GroupCreationMarketingExportFile file = service.exportTasks(request == null ? null : request.ids());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok().headers(headers).body(file.bytes());
    }

    /**
     * 查询建群营销任务详情。
     *
     * <p>详情包含任务主信息和按料子顺序排序的执行项明细,供页面抽屉展示。</p>
     *
     * @param id 建群营销任务 ID
     * @return 建群营销任务详情
     */
    @GetMapping("/{id}")
    public ApiResponse<GroupCreationMarketingTaskDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.getDetail(id));
    }
}
