package com.armada.contact.task.controller;

import com.armada.contact.task.model.dto.ContactTaskFormDTO;
import com.armada.contact.task.model.dto.ContactTaskQuery;
import com.armada.contact.task.model.vo.ContactTaskAccountItemVO;
import com.armada.contact.task.model.vo.ContactTaskDetailVO;
import com.armada.contact.task.model.vo.ContactTaskListItemVO;
import com.armada.contact.task.service.ContactTaskService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * 通讯录营销任务接口，只负责参数接收、可信身份衔接和统一响应封装。
 *
 * <p>与竞品的一处有意差异：竞品的创建与编辑走 {@code multipart/form-data}（因为要同时上传预览图），
 * 本接口只接 JSON，预览图由调用方先上传后传 {@code previewImageFileId}。
 * 图片上传接线在前端期一并落地，届时才知道前端实际怎么传。</p>
 *
 * <p><b>不提供删除接口</b>：竞品的任务 API 与行操作均没有删除。</p>
 */
@RestController
@RequestMapping("/api/contact-tasks")
@PreAuthorize("hasAuthority('tenant:contact_task:view')")
public class ContactTaskController {

    /** 通讯录营销任务业务服务。 */
    private final ContactTaskService service;

    /**
     * 创建通讯录营销任务控制器。
     *
     * @param service 通讯录营销任务业务服务
     */
    public ContactTaskController(ContactTaskService service) {
        this.service = service;
    }

    /**
     * 分页查询当前租户任务。
     *
     * @param query 任务名、状态、创建时间与分页条件
     * @return 当前页任务列表
     */
    @GetMapping
    public ApiResponse<PageResult<ContactTaskListItemVO>> list(
            @ModelAttribute ContactTaskQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /**
     * 查询任务完整详情。
     *
     * @param id 任务 ID
     * @return 任务详情
     */
    @GetMapping("/{id}")
    public ApiResponse<ContactTaskDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    /**
     * 创建任务，创建人取服务端认证建立的可信用户 ID。
     *
     * @param request 任务表单
     * @param principal 当前认证身份
     * @return 创建后的任务详情
     */
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:contact_task:create')")
    public ApiResponse<ContactTaskDetailVO> create(
            @RequestBody ContactTaskFormDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.create(request, principal.userId()));
    }

    /**
     * 编辑任务。仅未开始任务允许编辑，消息类型一律不可改。
     *
     * @param id 任务 ID
     * @param request 任务表单
     * @return 编辑后的任务详情
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:contact_task:edit')")
    public ApiResponse<ContactTaskDetailVO> update(
            @PathVariable Long id,
            @RequestBody ContactTaskFormDTO request) {
        return ApiResponse.ok(service.update(id, request));
    }

    /**
     * 执行任务动作：start / pause / resume / stop。
     *
     * @param id 任务 ID
     * @param request 动作请求体
     * @return 空响应
     */
    @PostMapping("/{id}/action")
    @PreAuthorize("hasAuthority('tenant:contact_task:operate')")
    public ApiResponse<Void> action(
            @PathVariable Long id,
            @RequestBody ContactTaskActionRequest request) {
        service.action(id, request == null ? null : request.action());
        return ApiResponse.ok(null);
    }

    /**
     * 分页查询任务的账号发送数据。
     *
     * @param id 任务 ID
     * @param sortBy 排序列，仅接受 needSendNum / sentNum / failNum
     * @param sortOrder 排序方向 asc / desc
     * @param page 页码
     * @param pageSize 每页条数
     * @return 当前页账号发送数据
     */
    @GetMapping("/{id}/data")
    public ApiResponse<PageResult<ContactTaskAccountItemVO>> accountData(
            @PathVariable Long id,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.ok(service.accountData(id, sortBy, sortOrder, page, pageSize));
    }

    /**
     * 试算账号范围命中数。
     *
     * <p>抽屉里改完筛选条件即时调用，用于显示「命中 N 个账号」并在命中 0 时阻止启用。
     * 走的是与启用时圈号完全相同的归一化与 SQL 条件。</p>
     *
     * @param request 账号筛选请求体
     * @return 命中账号数
     */
    @PostMapping("/account-preview")
    public ApiResponse<ContactAccountPreviewVO> previewAccounts(
            @RequestBody(required = false) ContactAccountPreviewRequest request) {
        String filterJson = request == null ? null : request.accountFilterJson();
        return ApiResponse.ok(new ContactAccountPreviewVO(service.previewAccountCount(filterJson)));
    }

    /**
     * 账号范围试算请求体。
     *
     * @param accountFilterJson 前端提交的原始筛选 JSON 字符串
     */
    public record ContactAccountPreviewRequest(String accountFilterJson) {
    }

    /**
     * 账号范围试算结果。
     *
     * @param matchedAccountCount 命中账号数
     */
    public record ContactAccountPreviewVO(int matchedAccountCount) {
    }

    /**
     * 任务动作请求体。
     *
     * @param action 动作名：start / pause / resume / stop
     */
    public record ContactTaskActionRequest(String action) {
    }
}
