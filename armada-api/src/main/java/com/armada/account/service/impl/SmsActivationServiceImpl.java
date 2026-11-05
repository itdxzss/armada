package com.armada.account.service.impl;

import com.armada.account.converter.SmsActivationConverter;
import com.armada.account.mapper.SmsActivationMapper;
import com.armada.account.model.dto.SmsActivationCreateDTO;
import com.armada.account.model.dto.SmsActivationQuery;
import com.armada.account.model.entity.SmsActivation;
import com.armada.account.model.enums.SmsActivationState;
import com.armada.account.model.vo.AccountRegistrationCatalogVO;
import com.armada.account.model.vo.SmsActivationOptionsVO;
import com.armada.account.model.vo.SmsActivationVO;
import com.armada.account.service.AccountRegistrationService;
import com.armada.account.service.SmsActivationService;
import com.armada.platform.sms.grizzly.GrizzlySmsClient;
import com.armada.platform.sms.grizzly.GrizzlySmsProperties;
import com.armada.platform.sms.grizzly.exception.GrizzlySmsException;
import com.armada.platform.sms.grizzly.model.GrizzlyActivation;
import com.armada.platform.sms.grizzly.model.GrizzlyNumberRequest;
import com.armada.platform.sms.grizzly.model.GrizzlyPriceTier;
import com.armada.platform.sms.grizzly.model.GrizzlySmsStatus;
import com.armada.platform.sms.grizzly.model.GrizzlyStatusUpdate;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 手动接码实现；国家与服务目录复用新号注册，开关只看 Grizzly 配置。 */
@Service
public class SmsActivationServiceImpl implements SmsActivationService {
    /** 只记录记录 ID、订单 ID 与错误分类，不记录号码或验证码。 */
    private static final Logger LOG = LoggerFactory.getLogger(SmsActivationServiceImpl.class);
    /** 平台未返回有效期时按 20 分钟处理。 */
    static final long DEFAULT_LIFETIME = Duration.ofMinutes(20).toMillis();
    /** 平台时间无时区，只用于求两个时间的差值。 */
    private static final DateTimeFormatter PROVIDER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Grizzly 未启用。 */
    static final String GRIZZLY_DISABLED = "GRIZZLY_DISABLED";
    /** Grizzly 未开放取号与状态变更。 */
    static final String PURCHASE_DISABLED = "GRIZZLY_PURCHASE_DISABLED";
    /** 手动接码数据访问。 */
    private final SmsActivationMapper mapper;
    /** 记录到响应的转换。 */
    private final SmsActivationConverter converter;
    /** 复用新号注册的 WhatsApp 美国目录。 */
    private final AccountRegistrationService registrations;
    /** 接码平台客户端。 */
    private final GrizzlySmsClient grizzly;
    /** 接码平台开关。 */
    private final GrizzlySmsProperties properties;

    /** 装配数据访问、目录服务与平台客户端。 */
    public SmsActivationServiceImpl(SmsActivationMapper mapper, SmsActivationConverter converter,
            AccountRegistrationService registrations, GrizzlySmsClient grizzly, GrizzlySmsProperties properties) {
        this.mapper = mapper;
        this.converter = converter;
        this.registrations = registrations;
        this.grizzly = grizzly;
        this.properties = properties;
    }

    @Override
    public SmsActivationOptionsVO options() {
        var catalog = registrations.catalog();
        String disabled = disabledReason();
        BigDecimal balance = null;
        if (properties.isEnabled()) {
            try { balance = grizzly.getBalance(); }
            catch (GrizzlySmsException exception) { LOG.warn("sms activation code=BALANCE_{}", exception.getReason()); }
        }
        return new SmsActivationOptionsVO(catalog.serviceCode(), catalog.countries(), balance, disabled.isEmpty(), disabled);
    }

    @Override
    public List<GrizzlyPriceTier> priceTiers(String countryId) {
        return registrations.priceTiers(countryId);
    }

    @Override
    public SmsActivationVO create(SmsActivationCreateDTO request, long operatorId) {
        validate(request);
        String disabled = disabledReason();
        if (!disabled.isEmpty()) { throw new BusinessException(ErrorCode.VALIDATION, "手动接码未开放：" + disabled); }
        AccountRegistrationCatalogVO catalog = registrations.catalog();
        if (catalog.countries().stream().noneMatch(country -> country.id().equals(request.countryId()))) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择当前美国目录中的国家渠道");
        }
        GrizzlyActivation activation = acquire(catalog.serviceCode(), request);
        SmsActivation row = newRow(activation, catalog.serviceCode(), request.countryId(), operatorId);
        try {
            mapper.insert(row);
        } catch (RuntimeException exception) {
            // 号码已扣费但未落库，只能人工到平台取消；日志只给订单 ID。
            LOG.warn("sms activation activationId={} code=SAVE_FAILED", activation.activationId());
            throw new BusinessException(ErrorCode.SMS_PROVIDER_UNAVAILABLE,
                    "号码已取得但保存失败，请到接码平台取消订单 " + activation.activationId());
        }
        return converter.vo(mapper.find(row.getId()));
    }

    @Override
    public PageResult<SmsActivationVO> list(SmsActivationQuery query) {
        long total = mapper.count();
        var rows = total == 0 ? List.<SmsActivationVO>of() : converter.vos(mapper.list(query));
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    @Override
    public SmsActivationVO refresh(Long id) {
        SmsActivation row = require(id);
        if (row.getState() == SmsActivationState.WAITING_CODE.code()) {
            poll(row);
            row = require(id);
        }
        return converter.vo(row);
    }

    @Override
    public SmsActivationVO complete(Long id) {
        SmsActivation row = require(id);
        requireState(row, SmsActivationState.RECEIVED, "只有已收到验证码的号码才能完成");
        grizzly.setStatus(row.getActivationId(), GrizzlyStatusUpdate.COMPLETE);
        transition(row, SmsActivationState.COMPLETED);
        return converter.vo(require(id));
    }

    @Override
    public SmsActivationVO cancel(Long id) {
        SmsActivation row = require(id);
        requireState(row, SmsActivationState.WAITING_CODE, "只有等码中的号码才能取消");
        long wait = row.getCancelAfter() - System.currentTimeMillis();
        if (wait > 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "平台限制，还需等待 " + Math.ceilDiv(wait, 1000) + " 秒才能取消");
        }
        grizzly.setStatus(row.getActivationId(), GrizzlyStatusUpdate.CANCEL);
        transition(row, SmsActivationState.CANCELLED);
        return converter.vo(require(id));
    }

    /** 等码记录：过期直接本地标记；否则查一次平台，失败保留原状态等下次轮询。 */
    private void poll(SmsActivation row) {
        if (System.currentTimeMillis() >= row.getExpiresAt()) { transition(row, SmsActivationState.EXPIRED); return; }
        GrizzlySmsStatus status;
        try {
            status = grizzly.getStatus(row.getActivationId());
        } catch (GrizzlySmsException exception) {
            LOG.warn("sms activation id={} code=STATUS_{}", row.getId(), exception.getReason());
            return;
        }
        if (status.state() == GrizzlySmsStatus.State.CANCELLED) { transition(row, SmsActivationState.CANCELLED); return; }
        // WAIT_RETRY 带的是旧码，不能当作新验证码写入。
        if (status.state() == GrizzlySmsStatus.State.RECEIVED && status.code().isPresent()) {
            row.setSmsCode(status.code().orElseThrow());
            row.setReceivedAt(System.currentTimeMillis());
            transition(row, SmsActivationState.RECEIVED);
        }
    }

    private GrizzlyActivation acquire(String serviceCode, SmsActivationCreateDTO request) {
        var providers = request.providerId() == null || request.providerId().isBlank()
                ? List.<String>of() : List.of(request.providerId());
        var command = new GrizzlyNumberRequest(serviceCode, request.countryId(), request.maxPrice(),
                new GrizzlyNumberRequest.Options(null, providers, List.of(), List.of()));
        try {
            return grizzly.acquireNumber(command);
        } catch (GrizzlySmsException exception) {
            throw switch (exception.getReason()) {
                case NO_NUMBERS -> new BusinessException(ErrorCode.VALIDATION, "当前价格暂无号码，请换价格档或稍后再试");
                case NO_BALANCE -> new BusinessException(ErrorCode.VALIDATION, "接码平台余额不足");
                default -> exception;
            };
        }
    }

    private static SmsActivation newRow(GrizzlyActivation activation, String serviceCode, String countryId, long operatorId) {
        long now = System.currentTimeMillis();
        var details = activation.details();
        var row = new SmsActivation();
        row.setActivationId(activation.activationId());
        row.setPhoneNumber(activation.phoneNumber());
        row.setServiceCode(serviceCode);
        row.setCountryId(countryId);
        row.setCost(activation.cost());
        row.setCurrency(activation.currency());
        row.setState(SmsActivationState.WAITING_CODE.code());
        row.setCancelAfter(now + offset(details.activationTime(), details.activationCancel()).orElse(0L));
        row.setExpiresAt(now + offset(details.activationTime(), details.activationEnd()).orElse(DEFAULT_LIFETIME));
        row.setCreatedBy(operatorId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /** 平台时间无时区，只取两个时间的差；缺失、无法解析或为负时返回空。 */
    static Optional<Long> offset(Optional<String> start, Optional<String> end) {
        if (start.isEmpty() || end.isEmpty()) { return Optional.empty(); }
        try {
            long delay = Duration.between(LocalDateTime.parse(start.orElseThrow(), PROVIDER_TIME),
                    LocalDateTime.parse(end.orElseThrow(), PROVIDER_TIME)).toMillis();
            return delay >= 0 ? Optional.of(delay) : Optional.empty();
        } catch (DateTimeParseException exception) {
            return Optional.empty();
        }
    }

    private void transition(SmsActivation row, SmsActivationState target) {
        int from = row.getState();
        row.setState(target.code());
        row.setUpdatedAt(System.currentTimeMillis());
        mapper.transition(row, from);
    }

    private SmsActivation require(Long id) {
        SmsActivation row = mapper.find(id);
        if (row == null) { throw new BusinessException(ErrorCode.NOT_FOUND); }
        return row;
    }

    private static void requireState(SmsActivation row, SmsActivationState expected, String message) {
        if (row.getState() != expected.code()) { throw new BusinessException(ErrorCode.CONFLICT, message); }
    }

    private String disabledReason() {
        if (!properties.isEnabled()) { return GRIZZLY_DISABLED; }
        return properties.isPurchasesEnabled() ? "" : PURCHASE_DISABLED;
    }

    private static void validate(SmsActivationCreateDTO request) {
        if (request == null || request.countryId() == null || request.countryId().isBlank()
                || request.maxPrice() == null || request.maxPrice().signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "取号参数无效");
        }
    }
}
