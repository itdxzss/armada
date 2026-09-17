package com.armada.account.controller;

import com.armada.account.model.dto.DeviceRegistrationPermit;
import com.armada.account.model.dto.DeviceRegistrationBeginDTO;
import com.armada.account.model.dto.DeviceRegistrationIdentity;
import com.armada.account.model.dto.DeviceRegistrationResultDTO;
import com.armada.account.model.dto.DeviceRegistrationStartDTO;
import com.armada.account.service.DeviceRegistrationPermitService;
import com.armada.platform.sms.grizzly.model.GrizzlyPriceTier;
import com.armada.account.model.vo.DeviceRegistrationOptionsVO;
import com.armada.account.model.vo.DeviceRegistrationVO;
import com.armada.account.service.DeviceRegistrationService;
import com.armada.shared.response.ApiResponse;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** 注册专用设备入口，只操作许可绑定的一笔任务。 */
@RestController
public class DeviceRegistrationController {
    /** 所有子路径均由专用安全链负责，包括无效路径。 */
    public static final String PREFIX = "/api/device-registrations/";
    /** 精确允许的端点。 */
    public static final Set<String> PATHS = Set.of(PREFIX + "status", PREFIX + "start", PREFIX + "result", PREFIX + "options", PREFIX + "begin");
    /** 认证链绑定的手机身份。 */
    public static final String IDENTITY = "deviceRegistrationIdentity";
    /** 手机明确提交的采购意图。 */
    public static final String BEGIN = "deviceRegistrationBegin";
    /** 过滤器产生的可信许可。 */
    public static final String PERMIT = "deviceRegistrationPermit";
    /** 过滤器严格限长解析的回报。 */
    public static final String RESULT = "deviceRegistrationResult";
    /** 过滤器解析的本次商家确认。 */
    public static final String START = "deviceRegistrationStart";
    private final DeviceRegistrationService service;
    private final DeviceRegistrationPermitService permits;
    /** 装配注册应用服务。 */
    public DeviceRegistrationController(DeviceRegistrationService service, DeviceRegistrationPermitService permits) {
        this.service = service; this.permits = permits;
    }

    /** 手机主动发起或恢复单号任务；许可由服务端自动准备。 */
    @PostMapping(path = PREFIX + "begin", consumes = "application/json", produces = "application/json")
    public ResponseEntity<ApiResponse<DeviceRegistrationVO>> begin(@RequestAttribute(IDENTITY) DeviceRegistrationIdentity identity,
            @RequestAttribute(BEGIN) DeviceRegistrationBeginDTO request) {
        return response(service.start(permits.begin(identity, request)));
    }

    /** 查询单任务状态，验证码响应禁止缓存。 */
    @PostMapping(path = PREFIX + "status", consumes = "application/json", produces = "application/json")
    public ResponseEntity<ApiResponse<DeviceRegistrationVO>> status(@RequestAttribute(PERMIT) DeviceRegistrationPermit permit) {
        return response(service.status(permit));
    }
    /** 显式提交许可，创建固定一个采购明细。 */
    @PostMapping(path = PREFIX + "start", consumes = "application/json", produces = "application/json")
    public ResponseEntity<ApiResponse<DeviceRegistrationVO>> start(@RequestAttribute(PERMIT) DeviceRegistrationPermit permit,
            @RequestAttribute(START) DeviceRegistrationStartDTO request) {
        return response(service.start(permits.select(permit, request)));
    }
    /** 返回本次授权价格下可选的实时商家，不采购。 */
    @PostMapping(path = PREFIX + "options", consumes = "application/json", produces = "application/json")
    public ResponseEntity<ApiResponse<DeviceRegistrationOptionsVO>> options(@RequestAttribute(PERMIT) DeviceRegistrationPermit permit) {
        var providers = permits.options(permit).stream().map(GrizzlyPriceTier::providerIds)
                .flatMap(java.util.Collection::stream).distinct().sorted().toList();
        var result = new DeviceRegistrationOptionsVO(permit.requestId(), permit.countryId(), permit.unitPrice(), providers);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(result));
    }
    /** 回报原生注册结果，此处为流程终点。 */
    @PostMapping(path = PREFIX + "result", consumes = "application/json", produces = "application/json")
    public ResponseEntity<ApiResponse<DeviceRegistrationVO>> result(@RequestAttribute(PERMIT) DeviceRegistrationPermit permit,
            @RequestAttribute(RESULT) DeviceRegistrationResultDTO result) {
        return response(service.result(permit, result));
    }
    private ResponseEntity<ApiResponse<DeviceRegistrationVO>> response(DeviceRegistrationVO result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(result));
    }
}
