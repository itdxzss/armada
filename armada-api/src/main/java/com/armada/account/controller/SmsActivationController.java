package com.armada.account.controller;

import com.armada.account.model.dto.SmsActivationCreateDTO;
import com.armada.account.model.dto.SmsActivationQuery;
import com.armada.account.model.vo.SmsActivationOptionsVO;
import com.armada.account.model.vo.SmsActivationVO;
import com.armada.account.service.SmsActivationService;
import com.armada.platform.sms.grizzly.model.GrizzlyPriceTier;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 后台手动接码接口：运营取号、轮询验证码、完成或取消。 */
@RestController
@RequestMapping("/api/sms-activations")
@PreAuthorize("hasAuthority('tenant:account:edit')")
public class SmsActivationController {
    /** 手动接码业务入口。 */
    private final SmsActivationService service;
    /** 装配业务服务。 */
    public SmsActivationController(SmsActivationService service) { this.service = service; }

    /** 页面初始化：国家目录、余额与取号开关。 */
    @GetMapping("/options")
    public ApiResponse<SmsActivationOptionsVO> options() { return ApiResponse.ok(service.options()); }

    /** 指定国家的价格档位，不预留库存。 */
    @GetMapping("/price-tiers")
    public ApiResponse<List<GrizzlyPriceTier>> tiers(@RequestParam String countryId) {
        return ApiResponse.ok(service.priceTiers(countryId));
    }

    /** 同步取一个号。 */
    @PostMapping
    public ApiResponse<SmsActivationVO> create(@RequestBody SmsActivationCreateDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.create(request, principal.userId()));
    }

    /** 当前租户接码记录分页。 */
    @GetMapping
    public ApiResponse<PageResult<SmsActivationVO>> list(@ModelAttribute SmsActivationQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /** 查询一条记录，等码中会向平台刷新一次。 */
    @GetMapping("/{id}")
    public ApiResponse<SmsActivationVO> refresh(@PathVariable Long id) { return ApiResponse.ok(service.refresh(id)); }

    /** 已收码订单通知平台完成。 */
    @PostMapping("/{id}/complete")
    public ApiResponse<SmsActivationVO> complete(@PathVariable Long id) { return ApiResponse.ok(service.complete(id)); }

    /** 等码订单通知平台取消。 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<SmsActivationVO> cancel(@PathVariable Long id) { return ApiResponse.ok(service.cancel(id)); }
}
