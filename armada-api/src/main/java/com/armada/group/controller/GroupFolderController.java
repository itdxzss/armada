package com.armada.group.controller;

import com.armada.group.model.dto.GroupFolderBatchDeleteDTO;
import com.armada.group.model.dto.GroupFolderDTO;
import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.vo.GroupFolderDeleteResultVO;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.group.model.vo.GroupFolderVO;
import com.armada.group.service.GroupFolderService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 群组列表运营分组管理端点。
 */
@RestController
@RequestMapping("/api/group-folders")
@PreAuthorize("hasAuthority('tenant:group_link:view')")
public class GroupFolderController {

    private final GroupFolderService service;

    public GroupFolderController(GroupFolderService service) {
        this.service = service;
    }

    /**
     * 分页查询运营分组及关联群数。
     */
    @GetMapping
    public ApiResponse<PageResult<GroupFolderVO>> list(@ModelAttribute GroupFolderQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /**
     * 查询群组筛选器和批量分组弹窗使用的全部活跃选项。
     */
    @GetMapping("/options")
    public ApiResponse<List<GroupFolderOptionVO>> options() {
        return ApiResponse.ok(service.options());
    }

    /**
     * 新建运营分组。
     */
    @PostMapping
    public ApiResponse<GroupFolderVO> create(@RequestBody GroupFolderDTO dto) {
        return ApiResponse.ok(service.create(dto));
    }

    /**
     * 修改运营分组名称。
     */
    @PatchMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody GroupFolderDTO dto) {
        service.update(id, dto);
        return ApiResponse.ok();
    }

    /**
     * 批量删除运营分组并将关联群组移入未分组。
     */
    @PostMapping("/batch-delete")
    public ApiResponse<GroupFolderDeleteResultVO> batchDelete(
            @RequestBody GroupFolderBatchDeleteDTO dto) {
        return ApiResponse.ok(service.batchDelete(dto == null ? null : dto.ids()));
    }
}
