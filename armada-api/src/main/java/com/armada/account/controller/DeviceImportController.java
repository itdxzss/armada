package com.armada.account.controller;

import com.armada.account.model.dto.DeviceImportDTO;
import com.armada.account.model.dto.DeviceImportDefaults;
import com.armada.account.model.dto.DeviceLogoutDTO;
import com.armada.account.model.vo.AccountGroupOptionVO;
import com.armada.account.model.vo.DeviceImportVO;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.DeviceImportService;
import java.util.List;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 手机凭据直传入口；鉴权由专用静态令牌链执行，不授予管理员权限。 */
@RestController
public class DeviceImportController {

    /** 手机上传路径。 */
    public static final String PATH = "/api/device-imports";
    /** 上传前选择当前令牌租户的账号分组。 */
    public static final String GROUPS_PATH = PATH + "/groups";
    /** 官方退出后的幂等放行入口。 */
    public static final String LOGOUT_PATH = PATH + "/logout-confirmed";
    /** 专用安全链与 Bearer 过滤器共用的精确路径集合。 */
    public static final Set<String> PATHS = Set.of(PATH, GROUPS_PATH, LOGOUT_PATH);
    /** 由过滤器设置的服务端默认配置，HTTP 输入不能覆盖此 request attribute。 */
    public static final String DEFAULTS_ATTRIBUTE = "deviceImportDefaults";
    private final DeviceImportService service;
    private final AccountGroupService groups;

    /** 复用原子导入和当前租户的分组候选项服务。 */
    public DeviceImportController(DeviceImportService service, AccountGroupService groups) {
        this.service = service;
        this.groups = groups;
    }

    /**
     * 查询令牌所属租户的有效分组，供手机在上传前选择，不创建默认组。
     * @return 仅含 id 和 name 的分组列表；没有分组时返回空数组
     */
    @GetMapping(path = GROUPS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AccountGroupOptionVO>> groups() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(groups.options());
    }

    /**
     * 接收单条全参并返回已提交、等待手机退出的受理结果。
     * @param request 手机请求体
     * @param defaults 静态令牌鉴权产生的服务器默认值
     * @return 不套管理员 ApiResponse 的冻结 JSON
     */
    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DeviceImportVO> importAccount(@RequestBody DeviceImportDTO request,
            @RequestAttribute(DEFAULTS_ATTRIBUTE) DeviceImportDefaults defaults) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.importAccount(request, defaults));
    }

    /** 手机确认官方退出后放行同租户批次，重复请求不重复上线。 */
    @PostMapping(path = LOGOUT_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DeviceImportVO> confirmLogout(@RequestBody DeviceLogoutDTO request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.confirmLogout(request.batchId()));
    }
}
