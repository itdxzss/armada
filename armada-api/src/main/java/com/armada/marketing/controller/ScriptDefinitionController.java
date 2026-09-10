package com.armada.marketing.controller;

import com.armada.marketing.model.dto.ScriptDefinitionSaveDTO;
import com.armada.marketing.model.dto.ScriptMarketingQuery;
import com.armada.marketing.model.vo.ScriptDefinitionVO;
import com.armada.marketing.model.vo.ScriptDefinitionSummaryVO;
import com.armada.marketing.script.service.ScriptDefinitionService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 独立养群剧本库，复用剧本营销权限和本人数据边界。 */
@RestController
@RequestMapping("/api/script-definitions")
@PreAuthorize("hasAuthority('tenant:script_marketing:view')")
public class ScriptDefinitionController {
    private final ScriptDefinitionService service;
    /** 注入剧本定义服务。 */
    public ScriptDefinitionController(ScriptDefinitionService service) { this.service = service; }
    /** 分页列出本人剧本。 */
    @GetMapping public ApiResponse<PageResult<ScriptDefinitionSummaryVO>> list(@ModelAttribute ScriptMarketingQuery query,
            @AuthenticationPrincipal AuthPrincipal principal) { return ApiResponse.ok(service.list(query, principal.userId())); }
    /** 读取选用或编辑所需的完整定义。 */
    @GetMapping("/{id}") public ApiResponse<ScriptDefinitionVO> detail(@PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) { return ApiResponse.ok(service.detail(id, principal.userId())); }
    /** 创建定义；复制由客户端显式保存新的完整定义。 */
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:script_marketing:view') and hasAuthority('tenant:script_marketing:create')")
    public ApiResponse<ScriptDefinitionVO> create(@RequestBody ScriptDefinitionSaveDTO dto,
            @AuthenticationPrincipal AuthPrincipal principal) { return ApiResponse.ok(service.create(dto, principal.userId())); }
    /** 更新本人定义，运行实例快照保持独立。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:script_marketing:view') and hasAuthority('tenant:script_marketing:edit')")
    public ApiResponse<ScriptDefinitionVO> update(@PathVariable Long id, @RequestBody ScriptDefinitionSaveDTO dto,
            @AuthenticationPrincipal AuthPrincipal principal) { return ApiResponse.ok(service.update(id, dto, principal.userId())); }
    /** 软删本人剧本。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:script_marketing:view') and hasAuthority('tenant:script_marketing:edit')")
    public ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal principal) {
        service.delete(id, principal.userId()); return ApiResponse.ok();
    }
}
