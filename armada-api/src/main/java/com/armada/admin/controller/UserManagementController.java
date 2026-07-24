package com.armada.admin.controller;

import com.armada.admin.model.dto.PasswordResetDTO;
import com.armada.admin.model.dto.StatusUpdateDTO;
import com.armada.admin.model.dto.UserCreateDTO;
import com.armada.admin.model.dto.UserUpdateDTO;
import com.armada.admin.model.vo.UserVO;
import com.armada.admin.service.UserManagementService;
import com.armada.shared.response.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 租户系统用户管理接口。 */
@RestController
@RequestMapping("/api/admin/users")
public class UserManagementController {

    private final UserManagementService service;

    public UserManagementController(UserManagementService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<UserVO>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<UserVO> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    public ApiResponse<UserVO> create(@RequestBody UserCreateDTO request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserVO> update(@PathVariable long id, @RequestBody UserUpdateDTO request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable long id, @RequestBody PasswordResetDTO request) {
        service.resetPassword(id, request == null ? null : request.newPassword());
        return ApiResponse.ok();
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<Void> changeStatus(@PathVariable long id, @RequestBody StatusUpdateDTO request) {
        service.changeStatus(id, request == null ? null : request.status());
        return ApiResponse.ok();
    }
}
