package com.armada.group.controller;

import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.dto.GroupFolderWriteDTO;
import com.armada.group.model.dto.GroupIdsDTO;
import com.armada.group.model.vo.GroupFolderDeleteVO;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.group.model.vo.GroupFolderVO;
import com.armada.group.service.GroupFolderService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 群组列表中的运营分组管理接口。 */
@RestController
@RequestMapping("/api/group-folders")
@PreAuthorize("hasAuthority('tenant:group_link:view')")
public class GroupFolderController {

    private final GroupFolderService service;

    public GroupFolderController(GroupFolderService service) {
        this.service = service;
    }

    /** 分页查询分组。 */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('tenant:group_link:view', 'tenant:pull_task:view')")
    public ApiResponse<PageResult<GroupFolderVO>> list(@ModelAttribute GroupFolderQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /** 查询创建拉群任务可选的分组。 */
    @GetMapping("/options")
    @PreAuthorize("hasAnyAuthority('tenant:group_link:view', 'tenant:pull_task:view')")
    public ApiResponse<List<GroupFolderOptionVO>> options() {
        return ApiResponse.ok(service.options());
    }

    /** 新建分组。 */
    @PostMapping
    public ApiResponse<GroupFolderVO> create(
            @RequestBody GroupFolderWriteDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.create(request, principal.userId()));
    }

    /** 修改分组名称。 */
    @PatchMapping("/{id}")
    public ApiResponse<Void> update(
            @PathVariable long id,
            @RequestBody GroupFolderWriteDTO request) {
        service.update(id, request);
        return ApiResponse.ok();
    }

    /** 批量删除分组，并解除群入口与这些分组的归属。 */
    @PostMapping("/batch-delete")
    public ApiResponse<GroupFolderDeleteVO> batchDelete(@RequestBody GroupIdsDTO request) {
        return ApiResponse.ok(service.batchDelete(request.ids()));
    }
}
