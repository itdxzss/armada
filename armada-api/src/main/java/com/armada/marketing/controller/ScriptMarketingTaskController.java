package com.armada.marketing.controller;

import com.armada.marketing.model.dto.ScriptMarketingQuery;
import com.armada.marketing.model.dto.ScriptMarketingSaveDTO;
import com.armada.marketing.model.vo.ScriptMarketingTaskVO;
import com.armada.marketing.model.vo.ScriptMarketingDetailVO;
import com.armada.marketing.model.vo.ScriptMarketingSendRecordVO;
import com.armada.marketing.script.service.ScriptMarketingTaskService;
import com.armada.marketing.script.service.ScriptMarketingExecutionService;
import com.armada.marketing.script.service.ScriptQualificationException;
import com.armada.marketing.model.vo.ScriptQualificationVO;
import org.springframework.web.bind.annotation.ExceptionHandler;
import com.armada.shared.paging.PageQuery;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 独立剧本任务入口，所有业务数据限当前租户及创建人。 */
@RestController
@RequestMapping("/api/script-marketing-tasks")
@PreAuthorize("hasAuthority('tenant:script_marketing:view')")
public class ScriptMarketingTaskController {
    private final ScriptMarketingTaskService service;
    private final ScriptMarketingExecutionService execution;
    /** 注入配置和执行操作。 */
    public ScriptMarketingTaskController(ScriptMarketingTaskService service, ScriptMarketingExecutionService execution) {
        this.service = service; this.execution = execution;
    }
    /** 分页查看本人任务。 */
    @GetMapping
    public ApiResponse<PageResult<ScriptMarketingTaskVO>> list(@ModelAttribute ScriptMarketingQuery query,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.list(query, principal.userId()));
    }
    /** 保存草稿，不触发实际发送。 */
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:script_marketing:view') and hasAuthority('tenant:script_marketing:create')")
    public ApiResponse<ScriptMarketingDetailVO> create(@RequestBody ScriptMarketingSaveDTO dto,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.create(dto, principal.userId()));
    }
    /** 保存前读取完整逐群资格，检查不会启动任务。 */
    @PostMapping("/check")
    public ApiResponse<ScriptQualificationVO> check(@RequestBody ScriptMarketingSaveDTO dto) {
        return ApiResponse.ok(service.check(dto));
    }
    /** 已保存配置重新检查，进群处理完成后仍需用户显式启动。 */
    @GetMapping("/{id}/check")
    public ApiResponse<ScriptQualificationVO> checkSaved(@PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.checkSaved(id, principal.userId()));
    }
    /** 启动复核拦截以非零业务码返回，并保留可展示的所有群缺口。 */
    @ExceptionHandler(ScriptQualificationException.class)
    public ApiResponse<ScriptQualificationVO> qualificationFailure(ScriptQualificationException exception) {
        return new ApiResponse<>(exception.getCode(), exception.getMessage(), exception.getReport());
    }
    /** 草稿配置编辑；运行后禁止变更发送顺序。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:script_marketing:view') and hasAuthority('tenant:script_marketing:edit')")
    public ApiResponse<ScriptMarketingDetailVO> update(@PathVariable Long id,
            @RequestBody ScriptMarketingSaveDTO dto, @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.update(id, dto, principal.userId()));
    }
    /** 查看本人任务配置与每群进度。 */
    @GetMapping("/{id}")
    public ApiResponse<ScriptMarketingDetailVO> detail(@PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.detail(id, principal.userId()));
    }
    /** 发送明细独立分页。 */
    @GetMapping("/{id}/records")
    public ApiResponse<PageResult<ScriptMarketingSendRecordVO>> records(@PathVariable Long id,
            @ModelAttribute PageQuery query, @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.records(id, query, principal.userId()));
    }
    /** 开始、暂停/人工接管、继续与关闭，只改变本人任务。 */
    @PostMapping("/{id}/{action:start|pause|resume|close}")
    @PreAuthorize("hasAuthority('tenant:script_marketing:view') and hasAuthority('tenant:script_marketing:operate')")
    public ApiResponse<Void> action(@PathVariable Long id, @PathVariable String action,
            @AuthenticationPrincipal AuthPrincipal principal) {
        execution.action(id, action, principal.userId());
        return ApiResponse.ok(null);
    }
    /** 暂停或继续单群；全任务暂停时不能单独启动群发送。 */
    @PostMapping("/{id}/groups/{groupId}/{action:pause|resume}")
    @PreAuthorize("hasAuthority('tenant:script_marketing:view') and hasAuthority('tenant:script_marketing:operate')")
    public ApiResponse<Void> groupAction(@PathVariable Long id, @PathVariable Long groupId,
            @PathVariable String action, @AuthenticationPrincipal AuthPrincipal principal) {
        execution.groupAction(id, groupId, action, principal.userId());
        return ApiResponse.ok();
    }
}
