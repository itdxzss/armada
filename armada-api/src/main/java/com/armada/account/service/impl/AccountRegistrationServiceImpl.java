package com.armada.account.service.impl;

import com.armada.account.converter.AccountRegistrationConverter;
import com.armada.account.mapper.AccountRegistrationMapper;
import com.armada.account.model.dto.AccountRegistrationCreateDTO;
import com.armada.account.model.dto.AccountRegistrationQuery;
import com.armada.account.model.entity.AccountRegistrationTask;
import com.armada.account.model.vo.AccountRegistrationCatalogVO;
import com.armada.account.model.vo.AccountRegistrationCountsVO;
import com.armada.account.model.vo.AccountRegistrationDetailVO;
import com.armada.account.model.vo.AccountRegistrationTaskVO;
import com.armada.account.service.AccountRegistrationService;
import com.armada.platform.registration.cobalt.CobaltRegistrationClient;
import com.armada.platform.sms.grizzly.GrizzlySmsClient;
import com.armada.platform.sms.grizzly.GrizzlySmsProperties;
import com.armada.platform.sms.grizzly.model.GrizzlyCountry;
import com.armada.platform.sms.grizzly.model.GrizzlyPriceTier;
import com.armada.platform.sms.grizzly.model.GrizzlyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 目录/报价校验与租户注册任务查询，不在HTTP请求中购买号码。 */
@Service
public class AccountRegistrationServiceImpl implements AccountRegistrationService {
    /** 单任务最大固定采购次数。 */
    private static final int MAX_QUANTITY = 100;
    /** 数据访问。 */
    private final AccountRegistrationMapper mapper;
    /** 响应白名单转换。 */
    private final AccountRegistrationConverter converter;
    /** 短事务写入。 */
    private final AccountRegistrationStore store;
    /** 供应商真实目录/报价。 */
    private final GrizzlySmsClient grizzly;
    /** 供应商功能开关。 */
    private final GrizzlySmsProperties grizzlyProperties;
    /** 注册服务能力检查。 */
    private final CobaltRegistrationClient cobalt;
    /** 显式业务采购开关。 */
    private final boolean enabled;

    /** 装配注册任务依赖，默认禁止采购。 */
    public AccountRegistrationServiceImpl(AccountRegistrationMapper mapper, AccountRegistrationConverter converter,
            AccountRegistrationStore store, GrizzlySmsClient grizzly, GrizzlySmsProperties grizzlyProperties,
            CobaltRegistrationClient cobalt, @Value("${armada.account.registration.enabled:false}") boolean enabled) {
        this.mapper = mapper;
        this.converter = converter;
        this.store = store;
        this.grizzly = grizzly;
        this.grizzlyProperties = grizzlyProperties;
        this.cobalt = cobalt;
        this.enabled = enabled;
    }

    @Override
    public AccountRegistrationCatalogVO catalog() {
        requireTenant();
        if (!grizzlyProperties.isEnabled()) {
            return new AccountRegistrationCatalogVO("", "WhatsApp", List.of(), false, "GRIZZLY_DISABLED");
        }
        GrizzlyService service = whatsapp();
        var countries = grizzly.getCountries().stream().filter(AccountRegistrationServiceImpl::isUnitedStates)
                .map(country -> new AccountRegistrationCatalogVO.Country(country.id(),
                        country.chineseName().orElse(country.englishName()))).toList();
        String disabled = orderingDisabledReason();
        return new AccountRegistrationCatalogVO(service.code(), service.name(), countries,
                disabled.isEmpty(), disabled);
    }

    @Override
    public List<GrizzlyPriceTier> priceTiers(String countryId) {
        requireTenant();
        var catalog = catalog();
        requireCountry(catalog, countryId);
        return grizzly.getPriceTiers(catalog.serviceCode(), countryId);
    }

    @Override
    public AccountRegistrationDetailVO create(AccountRegistrationCreateDTO request) {
        requireTenant();
        validate(request);
        AccountRegistrationTask existing = mapper.findByRequestId(request.requestId());
        if (existing != null) { AccountRegistrationStore.requireSame(existing, request); return detail(existing.getId()); }
        var catalog = catalog();
        if (!catalog.orderingEnabled()) {
            throw new BusinessException(ErrorCode.VALIDATION, "接码注册尚未开放，请检查服务配置与注册进程能力");
        }
        requireCountry(catalog, request.countryId());
        boolean available = grizzly.getPriceTiers(catalog.serviceCode(), request.countryId()).stream()
                .anyMatch(tier -> tier.cost().compareTo(request.unitPrice()) == 0 && tier.count() >= request.quantity());
        if (!available) { throw new BusinessException(ErrorCode.VALIDATION, "所选价格档位或库存已变化，请刷新"); }
        Long id;
        try { id = store.create(request, catalog.serviceCode()); }
        catch (DuplicateKeyException exception) {
            AccountRegistrationTask concurrent = mapper.findByRequestId(request.requestId());
            if (concurrent == null) { throw new BusinessException(ErrorCode.CONFLICT, "创建冲突，请使用相同请求ID核对"); }
            AccountRegistrationStore.requireSame(concurrent, request);
            id = concurrent.getId();
        }
        return detail(id);
    }

    @Override
    public PageResult<AccountRegistrationTaskVO> list(AccountRegistrationQuery query) {
        requireTenant();
        long total = mapper.countTasks();
        var tasks = total == 0 ? List.<AccountRegistrationTaskVO>of()
                : mapper.listTasks(query).stream().map(this::toTask).toList();
        return PageResult.of(tasks, query.getPage(), query.getPageSize(), total);
    }

    @Override
    public AccountRegistrationDetailVO detail(Long id) {
        requireTenant();
        AccountRegistrationTask task = mapper.findTask(id);
        if (task == null) { throw new BusinessException(ErrorCode.NOT_FOUND); }
        return new AccountRegistrationDetailVO(toTask(task), converter.items(mapper.listItems(id)));
    }

    @Override
    public AccountRegistrationDetailVO cancel(Long id) {
        requireTenant();
        store.cancel(id);
        return detail(id);
    }

    /** 每次实际采购前重新检查能力；暂时不可用保留PENDING，不产生号码费用。 */
    public String orderingDisabledReason() {
        if (!enabled) { return "REGISTRATION_DISABLED"; }
        if (!grizzlyProperties.isEnabled() || !grizzlyProperties.isPurchasesEnabled()) { return "GRIZZLY_PURCHASE_DISABLED"; }
        if (!cobalt.isEnabled()) { return "COBALT_DISABLED"; }
        try {
            var health = cobalt.health();
            if (!health.registrationEnabled()) { return "COBALT_REGISTRATION_DISABLED"; }
            return health.availableSlots() > 0 ? "" : "COBALT_BUSY";
        } catch (BusinessException exception) { return "COBALT_UNAVAILABLE"; }
    }

    private AccountRegistrationTaskVO toTask(AccountRegistrationTask task) {
        AccountRegistrationCountsVO counts = mapper.counts(task.getId());
        String status = taskStatus(counts, task.getQuantity());
        return converter.task(task, counts, status);
    }

    private static String taskStatus(AccountRegistrationCountsVO counts, int quantity) {
        if (counts.processing() > 0) { return "RUNNING"; }
        if (counts.pending() > 0) { return counts.pending() == quantity ? "PENDING" : "RUNNING"; }
        if (counts.unknown() > 0) { return "REVIEW_REQUIRED"; }
        return counts.cancelled() == quantity ? "CANCELLED" : "COMPLETED";
    }

    private GrizzlyService whatsapp() {
        return grizzly.getServices().stream().filter(service -> "whatsapp".equalsIgnoreCase(service.name().strip()))
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION, "平台未返回WhatsApp服务目录"));
    }

    private static boolean isUnitedStates(GrizzlyCountry country) {
        String name = country.englishName().strip().toUpperCase(Locale.ROOT);
        return name.equals("USA") || name.startsWith("USA ") || name.startsWith("USA(")
                || name.startsWith("UNITED STATES") || country.chineseName().orElse("").startsWith("美国");
    }

    private static void requireCountry(AccountRegistrationCatalogVO catalog, String id) {
        if (id == null || catalog.countries().stream().noneMatch(country -> country.id().equals(id))) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择当前美国目录中的国家渠道");
        }
    }

    private static void validate(AccountRegistrationCreateDTO request) {
        if (request == null || request.requestId() == null || request.unitPrice() == null
                || request.unitPrice().signum() <= 0 || request.unitPrice().scale() > 12
                || request.unitPrice().precision() > 30 || request.unitPrice().precision() - request.unitPrice().scale() > 18
                || request.quantity() == null || request.quantity() < 1 || request.quantity() > MAX_QUANTITY
                || request.accountGroupId() == null || request.accountGroupId() <= 0
                || request.accountType() == null || !List.of(1, 2).contains(request.accountType())
                || request.ipAllocationMode() == null || !List.of("smart", "mixed").contains(request.ipAllocationMode())
                || (request.ipRegion() != null && request.ipRegion().length() > 100)) {
            throw new BusinessException(ErrorCode.VALIDATION, "注册任务参数无效");
        }
        try {
            if (!UUID.fromString(request.requestId()).toString().equals(request.requestId())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) { throw new BusinessException(ErrorCode.VALIDATION, "请求ID必须为规范UUID"); }
    }

    private static void requireTenant() {
        if (TenantContext.get() == null || TenantContext.get() <= 0) { throw new BusinessException(ErrorCode.TENANT_MISSING); }
    }
}
