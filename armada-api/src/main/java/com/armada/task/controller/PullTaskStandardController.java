package com.armada.task.controller;

import com.armada.shared.response.ApiResponse;
import com.armada.shared.security.AuthPrincipal;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.vo.PullTaskStandardCreatedVO;
import com.armada.task.model.vo.PullTaskStandardDraftVO;
import com.armada.task.service.PullTaskStandardCreateService;
import com.armada.task.service.PullTaskStandardDraftService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 普通群链接拉群任务的创建接口。
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

    /**
     * 创建普通群链接任务创建接口。
     *
     * @param draftService  草稿编排服务
     * @param createService 提交冻结服务
     */
    public PullTaskStandardController(PullTaskStandardDraftService draftService,
                                      PullTaskStandardCreateService createService) {
        this.draftService = draftService;
        this.createService = createService;
    }

    /**
     * 解析本次粘贴的链接与上传的 TXT，把新配对增量追加到草稿。
     *
     * @param linksText 链接框全量文本，每次请求都要带
     * @param files     本次新增的 .txt 料子文件，可为空
     * @param principal 当前可信登录身份
     * @return 追加后的完整草稿视图
     */
    @PostMapping("/draft/plan")
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<PullTaskStandardDraftVO> plan(
            @RequestParam(value = "linksText", required = false) String linksText,
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(draftService.plan(linksText, toList(files),
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
