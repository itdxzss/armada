package com.armada.account.service.impl;

import com.armada.account.converter.SmsActivationConverter;
import com.armada.account.mapper.SmsActivationMapper;
import com.armada.account.mapper.SmsRegistrationMapper;
import com.armada.account.model.entity.SmsActivation;
import com.armada.account.model.entity.SmsRegistration;
import com.armada.account.model.enums.AccountTypeCode;
import com.armada.account.model.enums.SmsActivationState;
import com.armada.account.model.enums.SmsRegistrationState;
import com.armada.account.model.vo.SmsActivationVO;
import com.armada.account.service.SmsRegistrationService;
import com.armada.platform.country.service.CountryService;
import com.armada.platform.registration.go.GoRegistrationProperties;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 接码订单 Go 注册办理的开始入口与订单占用判断；后台推进见 {@link SmsRegistrationWorker}。 */
@Service
public class SmsRegistrationServiceImpl implements SmsRegistrationService {
    /** Go 注册未启用，或当前租户没有 Go 凭据。 */
    static final String REGISTRATION_DISABLED = "GO_REGISTRATION_DISABLED";
    /** 办理记录数据访问。 */
    private final SmsRegistrationMapper registrations;
    /** 接码订单数据访问；本类只读订单，不经 SmsActivationService，避免两个服务互相依赖。 */
    private final SmsActivationMapper activations;
    /** 订单实体到响应的转换。 */
    private final SmsActivationConverter converter;
    /** 代理池，跨域只走 Service。 */
    private final IpProxyService proxies;
    /** 号码区号到 IP 国家的解析。 */
    private final CountryService countries;
    /** Go 注册配置。 */
    private final GoRegistrationProperties properties;

    /** 装配数据访问、代理池与配置。 */
    public SmsRegistrationServiceImpl(SmsRegistrationMapper registrations, SmsActivationMapper activations,
            SmsActivationConverter converter, IpProxyService proxies, CountryService countries,
            GoRegistrationProperties properties) {
        this.registrations = registrations;
        this.activations = activations;
        this.converter = converter;
        this.proxies = proxies;
        this.countries = countries;
        this.properties = properties;
    }

    @Override
    public String disabledReason() {
        boolean usable = !availableAccountTypes().isEmpty();
        return usable ? "" : REGISTRATION_DISABLED;
    }

    @Override
    public List<Integer> availableAccountTypes() {
        if (!properties.isEnabled()) { return List.of(); }
        var tenant = properties.tenant(TenantContext.get());
        if (tenant.isEmpty()) { return List.of(); }
        List<Integer> types = new ArrayList<>();
        if (tenant.get().getProfileId() > 0) { types.add(AccountTypeCode.PERSONAL); }
        if (tenant.get().getBusinessProfileId() > 0) { types.add(AccountTypeCode.BUSINESS); }
        return List.copyOf(types);
    }

    /**
     * 代理占用与办理记录必须同事务：任何一步失败都整体回滚，不会留下被占住却无人使用的代理。
     * 注意不要在事务内吞掉唯一键异常后继续返回，否则代理占用会随事务提交而泄漏。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SmsActivationVO start(Long smsActivationId, long operatorId, int accountType) {
        if (accountType != AccountTypeCode.PERSONAL && accountType != AccountTypeCode.BUSINESS) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号类型必须为个人或商业");
        }
        if (!disabledReason().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "自动注册未开放：" + REGISTRATION_DISABLED);
        }
        SmsActivation order = activations.find(smsActivationId);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        SmsRegistration existing = registrations.findByActivation(order.getId());
        if (existing != null) {
            if (existing.getAccountType() != accountType) {
                throw new BusinessException(ErrorCode.CONFLICT, "该订单已按另一账号类型开始注册，不能更改");
            }
            // 重复点击：返回已有办理记录，不再占代理、不再发码。
            return view(order, existing);
        }
        if (!availableAccountTypes().contains(accountType)) {
            throw new BusinessException(ErrorCode.VALIDATION, "当前租户未配置所选账号类型的注册服务");
        }
        requireStartable(order);
        var proxy = proxies.allocateRegistrationEndpoint(order.getId(),
                countries.resolveIpRegionByPhonePrefix(order.getPhoneNumber()));
        SmsRegistration row = newRow(order, proxy.proxyId(), operatorId, accountType);
        try {
            registrations.insert(row);
        } catch (DuplicateKeyException exception) {
            // 并发双击：另一个请求已插入。抛出以回滚本事务内的代理占用。
            throw new BusinessException(ErrorCode.CONFLICT, "该订单正在办理注册");
        }
        return view(order, row);
    }

    @Override
    public boolean isOccupied(Long smsActivationId) {
        return registrations.countActiveByActivation(smsActivationId) > 0;
    }

    @Override
    public List<SmsActivationVO> attach(List<SmsActivationVO> views) {
        if (views.isEmpty()) {
            return views;
        }
        Map<Long, SmsRegistration> byActivation = registrations
                .listByActivationIds(views.stream().map(SmsActivationVO::id).toList()).stream()
                .collect(Collectors.toMap(SmsRegistration::getSmsActivationId, Function.identity()));
        return views.stream().map(view -> {
            SmsRegistration row = byActivation.get(view.id());
            return row == null ? view : decorate(view, row);
        }).toList();
    }

    private static void requireStartable(SmsActivation order) {
        if (order.getState() != SmsActivationState.WAITING_CODE.code() || order.getSmsCode() != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有等码中且尚未收到验证码的订单才能开始注册");
        }
        if (System.currentTimeMillis() >= order.getExpiresAt()) {
            throw new BusinessException(ErrorCode.CONFLICT, "订单已过期");
        }
    }

    private SmsRegistration newRow(SmsActivation order, Long proxyId, long operatorId, int accountType) {
        long now = System.currentTimeMillis();
        var row = new SmsRegistration();
        row.setSmsActivationId(order.getId());
        row.setState(SmsRegistrationState.REQUESTING.code());
        // 配置 ID 开始时固定，之后所有重放都用库里这个值，Go 的幂等指纹包含它。
        var tenant = properties.tenant(TenantContext.get()).orElseThrow();
        row.setAccountType(accountType);
        row.setGoProfileId(accountType == AccountTypeCode.BUSINESS ? tenant.getBusinessProfileId() : tenant.getProfileId());
        row.setProxyId(proxyId);
        row.setFailureCode("");
        row.setCreatedBy(operatorId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private SmsActivationVO view(SmsActivation order, SmsRegistration row) {
        return decorate(converter.vo(order), row);
    }

    private static SmsActivationVO decorate(SmsActivationVO view, SmsRegistration row) {
        return view.withRegistration(SmsRegistrationState.fromCode(row.getState()).name(),
                row.getFailureCode(), row.getAccountType());
    }
}
