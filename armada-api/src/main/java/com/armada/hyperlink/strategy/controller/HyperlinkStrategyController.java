package com.armada.hyperlink.strategy.controller;

import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyCreateDTO;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyQuery;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyUpdateDTO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyAccountContextVO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyDetailVO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyListItemVO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyOptionVO;
import com.armada.hyperlink.strategy.service.HyperlinkStrategyService;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.vo.HyperlinkAccountMatchCountVO;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 超链策略 CRUD、任务候选和账号筛选辅助接口。 */
@RestController
@RequestMapping("/api/hyperlink-strategies")
public class HyperlinkStrategyController {

    private final HyperlinkStrategyService service;

    public HyperlinkStrategyController(HyperlinkStrategyService service) {
        this.service = service;
    }

    /** 分页查询当前租户策略。 */
    @GetMapping
    @PreAuthorize("hasAuthority('tenant:hyperlink_strategy:view')")
    public ApiResponse<PageResult<HyperlinkStrategyListItemVO>> list(
            @ModelAttribute HyperlinkStrategyQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /** 查询当前租户策略完整详情。 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:hyperlink_strategy:view')")
    public ApiResponse<HyperlinkStrategyDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    /** 查询启用策略；策略页面和任务创建、编辑页面均可消费。 */
    @GetMapping("/options")
    @PreAuthorize("hasAuthority('tenant:hyperlink_strategy:view') or "
            + "hasAuthority('tenant:hyperlink_task:create') or "
            + "hasAuthority('tenant:hyperlink_task:edit')")
    public ApiResponse<List<HyperlinkStrategyOptionVO>> options(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(service.options(keyword, limit));
    }

    /** 返回不访问钱包的策略账号筛选下拉上下文。 */
    @GetMapping("/account-context")
    @PreAuthorize("hasAuthority('tenant:hyperlink_strategy:create') or "
            + "hasAuthority('tenant:hyperlink_strategy:edit')")
    public ApiResponse<HyperlinkStrategyAccountContextVO> accountContext() {
        return ApiResponse.ok(service.accountContext());
    }

    /** 按任务实际选号口径试算当前筛选匹配账号数。 */
    @PostMapping("/account-match-count")
    @PreAuthorize("hasAuthority('tenant:hyperlink_strategy:create') or "
            + "hasAuthority('tenant:hyperlink_strategy:edit')")
    public ApiResponse<HyperlinkAccountMatchCountVO> accountMatchCount(
            @RequestBody HyperlinkAccountFilterDTO request) {
        return ApiResponse.ok(service.accountMatchCount(request));
    }

    /** 创建策略，创建人来自服务端认证身份。 */
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:hyperlink_strategy:create')")
    public ApiResponse<HyperlinkStrategyDetailVO> create(
            @RequestBody HyperlinkStrategyCreateDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.create(request, principal.userId()));
    }

    /** 按请求 version 完整更新策略；启停也使用此接口。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:hyperlink_strategy:edit')")
    public ApiResponse<HyperlinkStrategyDetailVO> update(
            @PathVariable Long id,
            @RequestBody HyperlinkStrategyUpdateDTO request) {
        return ApiResponse.ok(service.update(id, request));
    }

    /** 软删除模板；任务关联独占快照，不回写也不阻断删除。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:hyperlink_strategy:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
