package com.armada.hyperlink.template.controller;

import com.armada.hyperlink.template.model.dto.HyperlinkTemplateCreateDTO;
import com.armada.hyperlink.template.model.dto.HyperlinkTemplateQuery;
import com.armada.hyperlink.template.model.dto.HyperlinkTemplateUpdateDTO;
import com.armada.hyperlink.template.model.vo.HyperlinkTemplateDetailVO;
import com.armada.hyperlink.template.model.vo.HyperlinkTemplateListItemVO;
import com.armada.hyperlink.template.model.vo.HyperlinkTemplateOptionVO;
import com.armada.hyperlink.template.service.HyperlinkTemplateService;
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

/** 超链营销模板菜单接口，只负责参数接收、可信身份衔接和统一响应封装。 */
@RestController
@RequestMapping("/api/hyperlink-templates")
@PreAuthorize("hasAuthority('tenant:hyperlink_template:view')")
public class HyperlinkTemplateController {

    /** 超链模板业务服务。 */
    private final HyperlinkTemplateService service;

    public HyperlinkTemplateController(HyperlinkTemplateService service) {
        this.service = service;
    }

    /**
     * 分页查询当前租户模板，筛选与分页均下推数据库。
     *
     * @param query 名称、类型、创建时间和分页条件
     * @return 当前页模板列表
     */
    @GetMapping
    public ApiResponse<PageResult<HyperlinkTemplateListItemVO>> list(
            @ModelAttribute HyperlinkTemplateQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /**
     * 查询当前租户模板完整详情。
     *
     * @param id 模板 ID
     * @return 完整消息内容和审计字段
     */
    @GetMapping("/{id}")
    public ApiResponse<HyperlinkTemplateDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    /**
     * 查询未来任务选择器使用的轻量模板候选。
     *
     * @param messageType 可选消息类型
     * @param keyword 可选名称或标题关键词
     * @param limit 返回上限，默认 50
     * @return 最近更新的候选列表
     */
    @GetMapping("/options")
    public ApiResponse<List<HyperlinkTemplateOptionVO>> options(
            @RequestParam(required = false) Integer messageType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(service.options(messageType, keyword, limit));
    }

    /**
     * 创建超链模板，创建人取服务端认证建立的可信用户 ID。
     *
     * @param request 完整模板内容
     * @param principal 当前认证身份
     * @return 创建后的模板详情
     */
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:hyperlink_template:create')")
    public ApiResponse<HyperlinkTemplateDetailVO> create(
            @RequestBody HyperlinkTemplateCreateDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.create(request, principal.userId()));
    }

    /**
     * 按请求版本完整更新模板。
     *
     * @param id 模板 ID
     * @param request 完整模板内容和当前版本
     * @return 更新后的模板详情
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:hyperlink_template:edit')")
    public ApiResponse<HyperlinkTemplateDetailVO> update(
            @PathVariable Long id,
            @RequestBody HyperlinkTemplateUpdateDTO request) {
        return ApiResponse.ok(service.update(id, request));
    }

    /**
     * 复制模板并由服务端生成递增副本名称。
     *
     * @param id 源模板 ID
     * @param principal 当前认证身份
     * @return 新副本详情
     */
    @PostMapping("/{id}/copy")
    @PreAuthorize("hasAuthority('tenant:hyperlink_template:copy')")
    public ApiResponse<HyperlinkTemplateDetailVO> copy(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.copy(id, principal.userId()));
    }

    /**
     * 软删除当前租户模板。
     *
     * @param id 模板 ID
     * @return data 固定为 null 的成功响应
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:hyperlink_template:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
