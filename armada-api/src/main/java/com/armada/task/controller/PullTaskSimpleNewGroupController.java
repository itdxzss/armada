package com.armada.task.controller;

import com.armada.shared.response.ApiResponse;
import com.armada.shared.security.AuthPrincipal;
import com.armada.task.model.dto.PullTaskSimpleNewGroupCreateDTO;
import com.armada.task.model.vo.PullTaskStandardCreatedVO;
import com.armada.task.service.impl.PullTaskDirectLinkCreateService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 新群模式（新）的无草稿创建接口。 */
@RestController
@RequestMapping("/api/pull-tasks/standard/simple-new-group")
public class PullTaskSimpleNewGroupController {
    private final PullTaskDirectLinkCreateService service;

    /** 注入直接创建业务服务。 */
    public PullTaskSimpleNewGroupController(PullTaskDirectLinkCreateService service) {
        this.service = service;
    }

    /** JSON 配置与 TXT 一次提交，成功后返回正式任务身份。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<PullTaskStandardCreatedVO> create(
            @RequestPart("request") PullTaskSimpleNewGroupCreateDTO request,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.create(request, files == null ? List.of() : files, principal));
    }
}
