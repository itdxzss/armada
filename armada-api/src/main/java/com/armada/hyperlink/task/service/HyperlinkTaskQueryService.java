package com.armada.hyperlink.task.service;

import com.armada.account.service.AccountGroupService;
import com.armada.hyperlink.data.model.vo.DataPackageDetailVO;
import com.armada.hyperlink.data.service.DataPackageService;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskButtonDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskMessageContentDTO;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskMode;
import com.armada.hyperlink.task.model.enums.HyperlinkProvisionStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskRunStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskStartMode;
import com.armada.hyperlink.task.model.vo.HyperlinkAccountMatchCountVO;
import com.armada.hyperlink.task.model.vo.HyperlinkCountryOptionVO;
import com.armada.hyperlink.task.model.vo.HyperlinkIdOptionVO;
import com.armada.hyperlink.task.model.vo.HyperlinkRecipientCountryCount;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskCreateContextVO;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskDetailRow;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskDetailVO;
import com.armada.hyperlink.task.model.vo.HyperlinkStringOptionVO;
import com.armada.hyperlink.task.port.HyperlinkWalletPort;
import com.armada.hyperlink.template.model.HyperlinkButton;
import com.armada.hyperlink.template.model.enums.HyperlinkButtonType;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.service.CountryService;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.util.HttpUrlValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** H2 新建上下文、账号试算和任务详情只读合同。 */
@Service
public class HyperlinkTaskQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(HyperlinkTaskQueryService.class);
    private static final int PROTOCOL_ACCOUNT_FACTOR = 15;
    private static final int DEFAULT_SUB_TASK_NUM = HyperlinkRecipientClaimService.BATCH_SIZE;
    private static final TypeReference<List<HyperlinkButton>> BUTTON_LIST = new TypeReference<>() { };

    private final HyperlinkTaskMapper taskMapper;
    private final DataPackageService dataPackageService;
    private final HyperlinkWalletPort walletPort;
    private final HyperlinkAccountCandidateSelector accountSelector;
    private final AccountGroupService accountGroupService;
    private final CountryService countryService;
    private final PromotionChannelService promotionChannelService;
    private final ObjectMapper objectMapper;
    private final HyperlinkAccountFilterNormalizer accountFilterNormalizer;
    private final Clock clock;

    @Autowired
    public HyperlinkTaskQueryService(HyperlinkTaskMapper taskMapper,
            DataPackageService dataPackageService,
            HyperlinkWalletPort walletPort,
            HyperlinkAccountCandidateSelector accountSelector,
            AccountGroupService accountGroupService,
            CountryService countryService,
            PromotionChannelService promotionChannelService,
            ObjectMapper objectMapper,
            HyperlinkAccountFilterNormalizer accountFilterNormalizer) {
        this(taskMapper, dataPackageService, walletPort, accountSelector, accountGroupService,
                countryService, promotionChannelService,
                objectMapper, accountFilterNormalizer, Clock.systemUTC());
    }

    HyperlinkTaskQueryService(HyperlinkTaskMapper taskMapper,
            DataPackageService dataPackageService,
            HyperlinkWalletPort walletPort,
            HyperlinkAccountCandidateSelector accountSelector,
            AccountGroupService accountGroupService,
            CountryService countryService,
            PromotionChannelService promotionChannelService,
            ObjectMapper objectMapper,
            HyperlinkAccountFilterNormalizer accountFilterNormalizer,
            Clock clock) {
        this.taskMapper = taskMapper;
        this.dataPackageService = dataPackageService;
        this.walletPort = walletPort;
        this.accountSelector = accountSelector;
        this.accountGroupService = accountGroupService;
        this.countryService = countryService;
        this.promotionChannelService = promotionChannelService;
        this.objectMapper = objectMapper;
        this.accountFilterNormalizer = accountFilterNormalizer;
        this.clock = clock;
    }

    /** 返回当前租户任务的完整表单事实；跨租户与不存在统一为 40401。 */
    public HyperlinkTaskDetailVO detail(long taskId) {
        long tenantId = requireTenant();
        if (taskId < 1) {
            throw validation("taskId 必须大于 0");
        }
        HyperlinkTaskDetailRow row = taskMapper.selectDetailById(tenantId, taskId);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "超链任务不存在");
        }
        HyperlinkAccountFilterDTO accountFilter = readAccountFilter(row.accountFilter());
        HyperlinkTaskMessageContentDTO messageContent = messageContent(row);
        DataPackageDisplay dataPackage = dataPackage(row);
        HyperlinkTaskRunStatus runStatus = HyperlinkTaskRunStatus.fromCode(row.runStatus());
        HyperlinkProvisionStatus provisionStatus =
                HyperlinkProvisionStatus.fromCode(row.provisionStatus());
        return new HyperlinkTaskDetailVO(row.id(), row.taskName(), row.messageType(),
                taskMode(row.taskType()).api(), Boolean.TRUE.equals(row.enabled()),
                runStatus.code(), Boolean.TRUE.equals(row.shortLinkEnabled()), row.version(),
                runStatus == HyperlinkTaskRunStatus.NOT_STARTED
                        && provisionStatus != HyperlinkProvisionStatus.PROCESSING,
                messageContent,
                row.taskPlannedEndAt(), row.taskIntervalMinutes(), accountFilter,
                seconds(row.msgIntervalMinMs()), seconds(row.msgIntervalMaxMs()),
                row.concurrentNum(), row.maxUseAccount(), row.accountMaxSendNum(),
                startMode(row.startMode()).api(), row.taskDelayMinutes(), row.dataPackageId(),
                dataPackage.name(), dataPackage.available(), row.createdAt(), row.updatedAt());
    }

    /** 返回真实钱包价码、余额和当前租户协议节点容量；依赖不可用时失败关闭。 */
    public HyperlinkTaskCreateContextVO createContext() {
        long tenantId = requireTenant();
        int protocolCount = accountSelector.protocolCount();
        HyperlinkWalletPort.PricingSnapshot pricing = walletPort.quote(
                tenantId, 1, List.<HyperlinkRecipientCountryCount>of());
        validatePricing(pricing);
        BigDecimal available = pricing.accountBalance().add(pricing.giftBalance());
        List<Long> defaultGroupIds = accountGroupService.hyperlinkDefaultGroupIds();
        List<HyperlinkIdOptionVO> groupOptions = accountGroupService.options().stream()
                .map(option -> new HyperlinkIdOptionVO(option.id(), option.name()))
                .toList();
        List<HyperlinkCountryOptionVO> countryOptions = countryService
                .options("marketing-export").rows().stream()
                .map(option -> new HyperlinkCountryOptionVO(option.value(), countryLabel(option),
                        option.flag(), option.continentCode()))
                .sorted(Comparator.comparing(HyperlinkCountryOptionVO::value))
                .toList();
        List<HyperlinkIdOptionVO> channelOptions = promotionChannelService.options().stream()
                .map(option -> new HyperlinkIdOptionVO(option.id(), option.name()))
                .toList();
        List<HyperlinkStringOptionVO> protocolOptions = accountSelector.protocolIds().stream()
                .map(value -> new HyperlinkStringOptionVO(value, value))
                .toList();
        return new HyperlinkTaskCreateContextVO(pricing.pricingMode(), pricing.priceCode(),
                pricing.currencyCode(), pricing.unitPrice(), pricing.accountBalance(),
                pricing.giftBalance(), available, protocolCount, capacity(protocolCount),
                HyperlinkTaskConfigurationFactory.ACCOUNT_SEND_CONCURRENCY,
                DEFAULT_SUB_TASK_NUM, defaultGroupIds,
                groupOptions, countryOptions, channelOptions,
                protocolOptions);
    }

    /** 使用运行选号的同一筛选归一化和 SQL，实时试算当前租户可用账号。 */
    public HyperlinkAccountMatchCountVO accountMatchCount(HyperlinkAccountFilterDTO filter) {
        long started = System.nanoTime();
        int available = accountSelector.count(filter, clock.millis());
        int protocolCount = accountSelector.protocolCount();
        LOGGER.info("超链账号试算 tenantId={} filterHash={} availableAccountCount={} "
                        + "protocolCount={} elapsedMs={}",
                requireTenant(), Integer.toUnsignedString(filter == null ? 0 : filter.hashCode(), 16),
                available, protocolCount, (System.nanoTime() - started) / 1_000_000L);
        return new HyperlinkAccountMatchCountVO(
                available, protocolCount, capacity(protocolCount));
    }

    private HyperlinkTaskMessageContentDTO messageContent(HyperlinkTaskDetailRow row) {
        if (!Integer.valueOf(1).equals(row.messageSchemaVersion())
                || row.messageType() == null || !Set.of(1, 2, 3, 4).contains(row.messageType())) {
            throw validation("任务消息内容版本或类型非法");
        }
        List<HyperlinkTaskButtonDTO> buttons = buttons(row.buttons());
        boolean buttonMessage = row.messageType() == 3 || row.messageType() == 4;
        if (buttonMessage && buttons.size() != 1) {
            throw validation("按钮任务必须恰好保存一个 CTA URL 按钮");
        }
        if (!buttonMessage && !buttons.isEmpty()) {
            throw validation("图文任务不能保存按钮");
        }
        return new HyperlinkTaskMessageContentDTO(row.linkPreviewAssetId(), row.title(),
                row.linkDescription(), row.promotionLink(), row.bodyMainAssetId(), row.content(),
                row.cardText(), buttons);
    }

    private List<HyperlinkTaskButtonDTO> buttons(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<HyperlinkButton> values = objectMapper.readValue(json, BUTTON_LIST);
            if (values == null) {
                throw validation("任务按钮 JSON 必须是数组");
            }
            return values.stream().map(this::button).toList();
        } catch (JsonProcessingException exception) {
            throw validation("任务按钮 JSON 无法解析");
        }
    }

    private HyperlinkTaskButtonDTO button(HyperlinkButton value) {
        if (value == null || value.type() != HyperlinkButtonType.CTA_URL
                || value.displayText() == null || value.displayText().isBlank()
                || !HttpUrlValidator.isHttpUrl(value.targetValue())
                || value.useShortLink() == null || !Integer.valueOf(1).equals(value.sort())) {
            throw validation("任务按钮快照非法");
        }
        return new HyperlinkTaskButtonDTO("CTA_URL", value.displayText(),
                value.targetValue(), value.useShortLink());
    }

    private HyperlinkAccountFilterDTO readAccountFilter(String json) {
        try {
            HyperlinkAccountFilterDTO value = objectMapper.readValue(json, HyperlinkAccountFilterDTO.class);
            return accountFilterNormalizer.normalize(value);
        } catch (JsonProcessingException exception) {
            throw validation("账号筛选快照无法解析");
        }
    }

    private DataPackageDisplay dataPackage(HyperlinkTaskDetailRow row) {
        if (row.dataPackageId() == null) {
            return new DataPackageDisplay(null, false);
        }
        try {
            DataPackageDetailVO detail = dataPackageService.detail(row.dataPackageId());
            boolean available = detail.metrics() != null && detail.metrics().unusedCount() > 0;
            return new DataPackageDisplay(detail.name(), available);
        } catch (BusinessException exception) {
            if (exception.getCode() != ErrorCode.NOT_FOUND.code()) {
                throw exception;
            }
            return new DataPackageDisplay(row.dataPackageNameSnapshot(), false);
        }
    }

    private void validatePricing(HyperlinkWalletPort.PricingSnapshot pricing) {
        if (pricing == null || !Set.of("NORMAL", "SUPER").contains(pricing.pricingMode())
                || pricing.priceCode() == null || pricing.priceCode().isBlank()
                || pricing.currencyCode() == null || pricing.currencyCode().isBlank()
                || pricing.unitPrice() == null || pricing.accountBalance() == null
                || pricing.giftBalance() == null) {
            throw new BusinessException(ErrorCode.HYPERLINK_BILLING_UNAVAILABLE,
                    "钱包未返回完整的新建任务上下文");
        }
    }

    private String countryLabel(CountryOptionVO option) {
        if (option.nameZh() != null && !option.nameZh().isBlank()) {
            return option.nameZh();
        }
        if (option.nameEn() != null && !option.nameEn().isBlank()) {
            return option.nameEn();
        }
        return option.value();
    }

    private HyperlinkTaskMode taskMode(Integer code) {
        for (HyperlinkTaskMode value : HyperlinkTaskMode.values()) {
            if (Integer.valueOf(value.code()).equals(code)) {
                return value;
            }
        }
        throw validation("任务模式快照非法");
    }

    private HyperlinkTaskStartMode startMode(Integer code) {
        for (HyperlinkTaskStartMode value : HyperlinkTaskStartMode.values()) {
            if (Integer.valueOf(value.code()).equals(code)) {
                return value;
            }
        }
        throw validation("任务启动方式快照非法");
    }

    private BigDecimal seconds(Integer millis) {
        if (millis == null) {
            throw validation("任务消息间隔快照缺失");
        }
        return BigDecimal.valueOf(millis, 3).stripTrailingZeros();
    }

    private int capacity(int protocolCount) {
        return Math.multiplyExact(protocolCount, PROTOCOL_ACCOUNT_FACTOR);
    }

    private long requireTenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId < 1) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return tenantId;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private record DataPackageDisplay(String name, boolean available) { }
}
