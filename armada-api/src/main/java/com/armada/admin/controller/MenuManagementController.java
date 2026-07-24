package com.armada.admin.controller;

import com.armada.admin.model.dto.MenuCreateDTO;
import com.armada.admin.model.dto.MenuUpdateDTO;
import com.armada.admin.model.dto.StatusUpdateDTO;
import com.armada.admin.model.vo.MenuTreeVO;
import com.armada.admin.service.MenuManagementService;
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

/** 租户菜单管理接口。 */
@RestController
@RequestMapping("/api/admin/menus")
public class MenuManagementController {

    private final MenuManagementService service;

    public MenuManagementController(MenuManagementService service) {
        this.service = service;
    }

    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('tenant:system-menu:view')")
    public ApiResponse<List<MenuTreeVO>> tree() {
        return ApiResponse.ok(service.tree());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('tenant:system-menu:create')")
    public ApiResponse<MenuTreeVO> create(@RequestBody MenuCreateDTO request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:system-menu:edit')")
    public ApiResponse<MenuTreeVO> update(@PathVariable long id, @RequestBody MenuUpdateDTO request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('tenant:system-menu:status')")
    public ApiResponse<Void> changeStatus(@PathVariable long id, @RequestBody StatusUpdateDTO request) {
        service.changeStatus(id, request == null ? null : request.status());
        return ApiResponse.ok();
    }
}
