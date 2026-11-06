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
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
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

    /** 装配数据访问与代理池。 */
    public SmsRegistrationServiceImpl(SmsRegistrationMapper registrations, SmsActivationMapper activations,
            SmsActivationConverter converter, IpProxyService proxies, CountryService countries) {
        this.registrations = registrations;
        this.activations = activations;
        this.converter = converter;
        this.proxies = proxies;
        this.countries = countries;
    }

    @Override
    public List<Integer> availableAccountTypes() {
        // Go 自动生成设备，个人版与商业版都不需要 Armada 侧配置。
        return List.of(AccountTypeCode.PERSONAL, AccountTypeCode.BUSINESS);
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
        row.setAccountType(accountType);
        // 设备由 Go 在首次发码时自动生成并随事务冻结，Armada 不再指定配置 ID；列保留为 0。
        row.setGoProfileId(0L);
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
