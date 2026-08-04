package com.armada.task.controller;

import com.armada.shared.response.ApiResponse;
import com.armada.shared.security.AuthPrincipal;
import com.armada.task.model.vo.PullTaskGroupAvatarContent;
import com.armada.task.model.vo.PullTaskGroupAvatarUploadVO;
import com.armada.task.service.PullTaskGroupAvatarService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 普通群链接任务群头像上传、预览和删除接口。 */
@RestController
@RequestMapping("/api/pull-tasks/standard/group-avatars")
public class PullTaskGroupAvatarController {

    private final PullTaskGroupAvatarService service;

    public PullTaskGroupAvatarController(PullTaskGroupAvatarService service) {
        this.service = service;
    }

    /** 上传一个待随整单表单绑定的头像。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<PullTaskGroupAvatarUploadVO> upload(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.upload(principal.tenantId(), file));
    }

    /** 预览当前租户保存的头像。 */
    @GetMapping("/{key}")
    @PreAuthorize("hasAuthority('tenant:pull_task:view')")
    public ResponseEntity<byte[]> content(
            @PathVariable String key,
            @AuthenticationPrincipal AuthPrincipal principal) {
        PullTaskGroupAvatarContent file = service.content(principal.tenantId(), key);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.noCache())
                .body(file.content());
    }

    /** 删除尚未绑定有效任务的头像。 */
    @DeleteMapping("/{key}")
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<Void> delete(
            @PathVariable String key,
            @AuthenticationPrincipal AuthPrincipal principal) {
        service.delete(principal.tenantId(), key);
        return ApiResponse.ok();
    }
}
