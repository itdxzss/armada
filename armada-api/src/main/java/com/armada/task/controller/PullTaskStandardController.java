package com.armada.task.controller;

import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.dto.PullTaskManagerSupplementDTO;
import com.armada.task.model.dto.PullTaskPullerSupplementDTO;
import com.armada.task.model.dto.PullTaskStationSupplementDTO;
import com.armada.task.model.dto.PullTaskStandardExecutionQuery;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.vo.PullTaskStandardCreatedVO;
import com.armada.task.model.vo.PullTaskStandardDraftVO;
import com.armada.task.model.vo.PullTaskStandardExecutionDetailVO;
import com.armada.task.model.vo.PullTaskStandardExecutionSummaryVO;
import com.armada.task.model.vo.PullTaskStandardMemberVO;
import com.armada.task.model.vo.PullTaskStandardTaskDetailVO;
import com.armada.task.model.vo.PullTaskManagerSupplementOptionsVO;
import com.armada.task.model.vo.PullTaskPullerSupplementOptionsVO;
import com.armada.task.model.vo.PullTaskStationSupplementOptionsVO;
import com.armada.task.service.PullTaskStandardCreateService;
import com.armada.task.service.PullTaskStandardDraftService;
import com.armada.task.service.PullTaskStandardReadService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 普通群链接拉群任务的创建、启动与 M1 详情接口。
 *
 * <p>与旧 {@code POST /api/pull-tasks}（只保存不透明 JSON 快照的 OLD_LINK / CREATE_NEW）
 * 完全隔离；等前端切换完成后由单独一个变更下线旧接口。</p>
 */
@RestController
@RequestMapping("/api/pull-tasks/standard")
@PreAuthorize("hasAuthority('tenant:pull_task:view')")
public class PullTaskStandardController {

    private final PullTaskStandardDraftService draftService;
    private final PullTaskStandardCreateService createService;
    private final PullTaskStandardOperationServices operationServices;
    private final PullTaskStandardReadService readService;

    /**
     * 创建普通群链接任务接口。
     *
     * @param draftService  草稿编排服务
     * @param createService 提交冻结服务
     * @param operationServices 启动与两级生命周期服务
     * @param readService   M1 详情读服务
     */
    public PullTaskStandardController(PullTaskStandardDraftService draftService,
                                      PullTaskStandardCreateService createService,
                                      PullTaskStandardOperationServices operationServices,
                                      PullTaskStandardReadService readService) {
        this.draftService = draftService;
        this.createService = createService;
        this.operationServices = operationServices;
        this.readService = readService;
    }

    /**
     * 解析本次粘贴的链接与上传的 TXT，把新配对增量追加到草稿。
     *
     * @param groupFolderId 群组列表运营分组 ID，可为空
     * @param linksText 链接框全量文本，每次请求都要带
     * @param files     本次新增的 .txt 料子文件，可为空
     * @param principal 当前可信登录身份
     * @return 追加后的完整草稿视图
     */
    @PostMapping("/draft/plan")
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<PullTaskStandardDraftVO> plan(
            @RequestParam(value = "creationMode", required = false)
            PullTaskCreationMode creationMode,
            @RequestParam(value = "groupFolderId", required = false) Long groupFolderId,
            @RequestParam(value = "linksText", required = false) String linksText,
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(draftService.plan(creationMode, groupFolderId, linksText, toList(files),
                principal.userId(), displayName(principal)));
    }

    /**
     * 回读当前用户的草稿。
     *
     * @param principal 当前可信登录身份
     * @return 草稿视图；没有草稿时 draftTaskId 为 null
     */
    @GetMapping("/draft")
    public ApiResponse<PullTaskStandardDraftVO> draft(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(draftService.current(principal.userId()));
    }

    /**
     * 移除草稿中的单条执行行。
     *
     * @param rowId     执行行 ID
     * @param principal 当前可信登录身份
     * @return 移除后的草稿视图
     */
    @DeleteMapping("/draft/rows/{rowId}")
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<PullTaskStandardDraftVO> removeRow(
            @PathVariable Long rowId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(draftService.removeRow(rowId, principal.userId()));
    }

    /**
     * 清空草稿中的全部执行行与料子。
     *
     * @param principal 当前可信登录身份
     * @return 清空后的草稿视图
     */
    @DeleteMapping("/draft")
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<PullTaskStandardDraftVO> clear(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(draftService.clear(principal.userId()));
    }

    /**
     * 把草稿提交为待启动任务。
     *
     * @param request   提交入参
     * @param principal 当前可信登录身份
     * @return 创建完成的任务行
     */
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<PullTaskStandardCreatedVO> create(
            @RequestBody PullTaskStandardCreateDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(createService.create(request, principal.userId()));
    }

    /**
     * 手动启动待开始的普通群链接任务。
     *
     * @param taskId 任务 ID
     * @return 空成功响应
     */
    @PostMapping("/{taskId}/start")
    @PreAuthorize("hasAuthority('tenant:pull_task:operate')")
    public ApiResponse<Void> start(@PathVariable Long taskId) {
        operationServices.startService().start(taskId);
        return ApiResponse.ok();
    }

    /** 暂停任务并保留全部执行检查点。 */
    @PostMapping("/{taskId}/pause")
    @PreAuthorize("hasAuthority('tenant:pull_task:operate')")
    public ApiResponse<Void> pause(@PathVariable Long taskId) {
        operationServices.lifecycleService().pause(taskId);
        return ApiResponse.ok();
    }

    /** 解除人工暂停并唤醒资源复核调度。 */
    @PostMapping("/{taskId}/resume")
    @PreAuthorize("hasAuthority('tenant:pull_task:operate')")
    public ApiResponse<Void> resume(@PathVariable Long taskId) {
        operationServices.lifecycleService().resume(taskId);
        return ApiResponse.ok();
    }

    /** 永久结束任务并取消尚未提交的动作。 */
    @PostMapping("/{taskId}/end")
    @PreAuthorize("hasAuthority('tenant:pull_task:operate')")
    public ApiResponse<Void> end(@PathVariable Long taskId) {
        operationServices.lifecycleService().end(taskId);
        return ApiResponse.ok();
    }

    /** 暂停单条群执行行并保留全部检查点。 */
    @PostMapping("/{taskId}/executions/{executionId}/pause")
    @PreAuthorize("hasAuthority('tenant:pull_task:operate')")
    public ApiResponse<Void> pauseExecution(
            @PathVariable Long taskId, @PathVariable Long executionId) {
        operationServices.executionLifecycleService().pause(taskId, executionId);
        return ApiResponse.ok();
    }

    /** 解除单群人工暂停；父任务暂停时仍不会自动调度。 */
    @PostMapping("/{taskId}/executions/{executionId}/resume")
    @PreAuthorize("hasAuthority('tenant:pull_task:operate')")
    public ApiResponse<Void> resumeExecution(
            @PathVariable Long taskId, @PathVariable Long executionId) {
        operationServices.executionLifecycleService().resume(taskId, executionId);
        return ApiResponse.ok();
    }

    /** 永久放弃单条群执行行并取消尚未提交的动作。 */
    @PostMapping("/{taskId}/executions/{executionId}/end")
    @PreAuthorize("hasAuthority('tenant:pull_task:operate')")
    public ApiResponse<Void> endExecution(
            @PathVariable Long taskId, @PathVariable Long executionId) {
        operationServices.executionLifecycleService().end(taskId, executionId);
        return ApiResponse.ok();
    }

    /** @return 任务事实与执行行摘要 */
    @GetMapping("/{taskId}")
    public ApiResponse<PullTaskStandardTaskDetailVO> detail(@PathVariable Long taskId) {
        return ApiResponse.ok(readService.task(taskId));
    }

    /** @return 服务端筛选、分页并聚合资源/料子结果的单群执行工作台 */
    @GetMapping("/{taskId}/executions")
    public ApiResponse<PageResult<PullTaskStandardExecutionSummaryVO>> executions(
            @PathVariable Long taskId,
            @ModelAttribute PullTaskStandardExecutionQuery query) {
        return ApiResponse.ok(readService.executions(taskId, query));
    }

    /** @return 单执行行、角色账号和逐调用事实 */
    @GetMapping("/{taskId}/executions/{executionId}")
    public ApiResponse<PullTaskStandardExecutionDetailVO> execution(
            @PathVariable Long taskId,
            @PathVariable Long executionId) {
        return ApiResponse.ok(readService.execution(taskId, executionId));
    }

    /** @return 单执行行的逐料子入群与提权结果 */
    @GetMapping("/{taskId}/executions/{executionId}/members")
    public ApiResponse<List<PullTaskStandardMemberVO>> members(
            @PathVariable Long taskId,
            @PathVariable Long executionId) {
        return ApiResponse.ok(readService.members(taskId, executionId));
    }

    /** @return 补充管理员页的缺口、当前执行账号和在线正常候选 */
    @GetMapping("/{taskId}/executions/{executionId}/manager-supplement/options")
    public ApiResponse<PullTaskManagerSupplementOptionsVO> managerSupplementOptions(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @RequestParam(value = "accountGroupId", required = false) Long accountGroupId) {
        return ApiResponse.ok(operationServices.supplementServices().managerService()
                .options(taskId, executionId, accountGroupId));
    }

    /** 保存不可变补充管理员指令并唤醒共享调度器。 */
    @PostMapping("/{taskId}/executions/{executionId}/manager-supplement")
    @PreAuthorize("hasAuthority('tenant:pull_task:operate')")
    public ApiResponse<Void> supplementManager(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @RequestBody PullTaskManagerSupplementDTO request) {
        operationServices.supplementServices().managerService()
                .supplement(taskId, executionId, request);
        return ApiResponse.ok();
    }

    /** @return 补充拉手页的缺口、候选账号和可用进群分支 */
    @GetMapping("/{taskId}/executions/{executionId}/puller-supplement/options")
    public ApiResponse<PullTaskPullerSupplementOptionsVO> pullerSupplementOptions(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @RequestParam(value = "accountGroupId", required = false) Long accountGroupId) {
        return ApiResponse.ok(operationServices.supplementServices().pullerService()
                .options(taskId, executionId, accountGroupId));
    }

    /** 保存补充拉手的选号与进群方式并回到管理—拉手联系人检查点。 */
    @PostMapping("/{taskId}/executions/{executionId}/puller-supplement")
    @PreAuthorize("hasAuthority('tenant:pull_task:operate')")
    public ApiResponse<Void> supplementPuller(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @RequestBody PullTaskPullerSupplementDTO request) {
        operationServices.supplementServices().pullerService()
                .supplement(taskId, executionId, request);
        return ApiResponse.ok();
    }

    /** @return 补充站台页的当前缺口和额外候选账号 */
    @GetMapping("/{taskId}/executions/{executionId}/station-supplement/options")
    public ApiResponse<PullTaskStationSupplementOptionsVO> stationSupplementOptions(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @RequestParam(value = "accountGroupId", required = false) Long accountGroupId) {
        return ApiResponse.ok(operationServices.supplementServices().stationService()
                .options(taskId, executionId, accountGroupId));
    }

    /** 只锁定补充站台并恢复拉人检查点，不直接改变群成员。 */
    @PostMapping("/{taskId}/executions/{executionId}/station-supplement")
    @PreAuthorize("hasAuthority('tenant:pull_task:operate')")
    public ApiResponse<Void> supplementStation(
            @PathVariable Long taskId,
            @PathVariable Long executionId,
            @RequestBody PullTaskStationSupplementDTO request) {
        operationServices.supplementServices().stationService()
                .supplement(taskId, executionId, request);
        return ApiResponse.ok();
    }

    /**
     * 把可空的文件数组收敛为不可变列表。
     *
     * @param files 上传文件数组，可为 null
     * @return 文件列表；无文件时为空列表而不是 null
     */
    private static List<MultipartFile> toList(MultipartFile[] files) {
        return files == null ? List.of() : List.of(files);
    }

    /**
     * 取操作员展示名快照。
     *
     * @param principal 当前可信登录身份
     * @return 昵称；昵称为空时退回登录名
     */
    private static String displayName(AuthPrincipal principal) {
        String nickname = principal.nickname();
        return nickname == null || nickname.isBlank() ? principal.username() : nickname;
    }
}
