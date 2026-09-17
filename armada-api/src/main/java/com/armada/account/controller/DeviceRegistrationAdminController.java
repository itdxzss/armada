package com.armada.account.controller;

import com.armada.account.model.dto.DeviceRegistrationIdentity;
import com.armada.account.model.dto.DeviceRegistrationSetupDTO;
import com.armada.account.model.dto.DeviceRegistrationStartDTO;
import com.armada.account.model.vo.DeviceRegistrationVO;
import com.armada.account.service.DeviceRegistrationPermitService;
import com.armada.account.service.DeviceRegistrationService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 控端单设备取号验收与许可管理；不触发 Cobalt 或注册后操作。 */
@RestController
@RequestMapping("/api/account-registrations/devices")
@PreAuthorize("hasAuthority('tenant:account:edit')")
public class DeviceRegistrationAdminController {
    private final DeviceRegistrationPermitService permits;
    private final DeviceRegistrationService registration;
    /** 装配领域服务，控制器不读取数据库。 */
    public DeviceRegistrationAdminController(DeviceRegistrationPermitService permits, DeviceRegistrationService registration) {
        this.permits = permits; this.registration = registration;
    }
    /** 保存单次许可，仅授权，不购买。 */
    @PostMapping
    public ApiResponse<DeviceRegistrationVO> prepare(@RequestBody DeviceRegistrationSetupDTO request) {
        return ApiResponse.ok(registration.inspect(permits.prepare(request)));
    }
    /** 读取本租户设备当前任务，不轮询短信或暴露验证码。 */
    @GetMapping("/{deviceId}")
    public ApiResponse<DeviceRegistrationVO> current(@PathVariable String deviceId) {
        return ApiResponse.ok(registration.inspect(permits.current(new DeviceRegistrationIdentity(TenantContext.get(), deviceId))));
    }
    /** 用户明确确认后在控端验证取号，重复请求恢复同一笔任务。 */
    @PostMapping("/{deviceId}/start")
    public ApiResponse<DeviceRegistrationVO> start(@PathVariable String deviceId, @RequestBody DeviceRegistrationStartDTO request) {
        var permit = permits.current(new DeviceRegistrationIdentity(TenantContext.get(), deviceId));
        return ApiResponse.ok(registration.start(permits.select(permit, request)));
    }
}
