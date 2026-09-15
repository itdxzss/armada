package com.armada.account.controller;

import com.armada.account.model.dto.AccountRegistrationCreateDTO;
import com.armada.account.model.dto.AccountRegistrationQuery;
import com.armada.account.model.vo.AccountRegistrationCatalogVO;
import com.armada.account.model.vo.AccountRegistrationDetailVO;
import com.armada.account.model.vo.AccountRegistrationTaskVO;
import com.armada.account.service.AccountRegistrationService;
import com.armada.platform.sms.grizzly.model.GrizzlyPriceTier;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 租户接码注册控制端接口；创建任务不在请求线程采购或注册。 */
@RestController
@RequestMapping("/api/account-registrations")
@PreAuthorize("hasAuthority('tenant:account:edit')")
public class AccountRegistrationController {
    /** 任务业务入口。 */
    private final AccountRegistrationService service;
    /** 装配业务服务。 */
    public AccountRegistrationController(AccountRegistrationService service) { this.service = service; }
    /** 获取真实WhatsApp美国目录与门禁。 */
    @GetMapping("/catalog")
    public ApiResponse<AccountRegistrationCatalogVO> catalog() { return ApiResponse.ok(service.catalog()); }
    /** 查询指定美国目录项的价档，不预留库存。 */
    @GetMapping("/price-tiers")
    public ApiResponse<List<GrizzlyPriceTier>> tiers(@RequestParam String countryId) {
        return ApiResponse.ok(service.priceTiers(countryId));
    }
    /** 幂等创建固定采购次数任务。 */
    @PostMapping
    public ApiResponse<AccountRegistrationDetailVO> create(@RequestBody AccountRegistrationCreateDTO request) {
        return ApiResponse.ok(service.create(request));
    }
    /** 查询当前租户任务分页。 */
    @GetMapping
    public ApiResponse<PageResult<AccountRegistrationTaskVO>> list(@ModelAttribute AccountRegistrationQuery query) {
        return ApiResponse.ok(service.list(query));
    }
    /** 查询任务完整进度，不包含OTP或六段。 */
    @GetMapping("/{id}")
    public ApiResponse<AccountRegistrationDetailVO> detail(@PathVariable Long id) { return ApiResponse.ok(service.detail(id)); }
    /** 停止尚未采购的条目，已在途流程继续收尾。 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<AccountRegistrationDetailVO> cancel(@PathVariable Long id) { return ApiResponse.ok(service.cancel(id)); }
}
