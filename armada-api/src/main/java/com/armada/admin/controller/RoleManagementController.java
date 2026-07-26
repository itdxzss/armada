package com.armada.admin.controller;

import com.armada.admin.model.dto.RoleCreateDTO;
import com.armada.admin.model.dto.RoleMenuGrantDTO;
import com.armada.admin.model.dto.RoleUpdateDTO;
import com.armada.admin.model.dto.StatusUpdateDTO;
import com.armada.admin.model.vo.RoleVO;
import com.armada.admin.service.RoleManagementService;
import com.armada.shared.response.ApiResponse;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 租户角色管理接口。 */
@RestController
@RequestMapping("/api/admin/roles")
public class RoleManagementController {

    private final RoleManagementService service;

    public RoleManagementController(RoleManagementService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('tenant:system-role:view')")
    public ApiResponse<List<RoleVO>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('tenant:system-role:create')")
    public ApiResponse<RoleVO> create(@RequestBody RoleCreateDTO request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:system-role:edit')")
    public ApiResponse<RoleVO> update(@PathVariable long id, @RequestBody RoleUpdateDTO request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('tenant:system-role:status')")
    public ApiResponse<Void> changeStatus(@PathVariable long id, @RequestBody StatusUpdateDTO request) {
        service.changeStatus(id, request == null ? null : request.status());
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/menus")
    @PreAuthorize("hasAuthority('tenant:system-role:view')")
    public ApiResponse<List<Long>> getMenuIds(@PathVariable long id) {
        return ApiResponse.ok(service.getMenuIds(id));
    }

    @PutMapping("/{id}/menus")
    @PreAuthorize("hasAuthority('tenant:system-role:grant')")
    public ApiResponse<Void> replaceMenus(@PathVariable long id, @RequestBody RoleMenuGrantDTO request) {
        service.replaceMenus(id, request == null ? null : request.menuIds());
        return ApiResponse.ok();
    }
}
