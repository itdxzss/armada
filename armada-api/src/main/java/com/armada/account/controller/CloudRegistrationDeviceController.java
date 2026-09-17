package com.armada.account.controller;

import com.armada.account.model.vo.CloudRegistrationDeviceVO;
import com.armada.account.service.CloudRegistrationDeviceService;
import com.armada.shared.response.ApiResponse;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 控端多选目录；保存许可与付费取号仍使用既有逐设备接口。 */
@RestController
@RequestMapping("/api/account-registrations/cloud-phones")
@PreAuthorize("hasAuthority('tenant:account:edit')")
public class CloudRegistrationDeviceController {
    private final CloudRegistrationDeviceService devices;

    /** 注入本租户设备目录。 */
    public CloudRegistrationDeviceController(CloudRegistrationDeviceService devices) { this.devices = devices; }

    /** 不查询接码商、不发起采购，也不暴露注册令牌。 */
    @GetMapping
    public ApiResponse<List<CloudRegistrationDeviceVO>> list() { return ApiResponse.ok(devices.currentDevices()); }
}
