package com.armada.hyperlink.task.service;

import com.armada.hyperlink.data.model.vo.DataPackageClaimSnapshot;
import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskQuoteDTO;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskMode;
import com.armada.hyperlink.task.model.vo.HyperlinkRecipientCountryCount;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskQuoteBreakdownVO;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskQuoteVO;
import com.armada.hyperlink.task.port.HyperlinkWalletPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 按可领取快照和钱包价码生成服务端报价。 */
@Service
public class HyperlinkTaskQuoteService {
    private static final long QUOTE_TTL_MS = 5 * 60 * 1000L;
    private final DataPackageRecipientClaimService dataPackageService;
    private final HyperlinkTaskMapper taskMapper;
    private final HyperlinkWalletPort walletPort;
    private final HyperlinkQuoteTokenService tokenService;
    private final HyperlinkOwnedRecipientQuoteService ownedRecipientQuoteService;
    private final HyperlinkProtocolCapacityService capacityService;
    private final Clock clock;

    @Autowired
    public HyperlinkTaskQuoteService(DataPackageRecipientClaimService dataPackageService,
            HyperlinkTaskMapper taskMapper,
            HyperlinkWalletPort walletPort,
            HyperlinkQuoteTokenService tokenService,
            HyperlinkOwnedRecipientQuoteService ownedRecipientQuoteService,
            HyperlinkProtocolCapacityService capacityService) {
        this(dataPackageService, taskMapper, walletPort, tokenService,
                ownedRecipientQuoteService, capacityService, Clock.systemUTC());
    }

    HyperlinkTaskQuoteService(DataPackageRecipientClaimService dataPackageService,
            HyperlinkTaskMapper taskMapper, HyperlinkWalletPort walletPort,
            HyperlinkQuoteTokenService tokenService,
            HyperlinkOwnedRecipientQuoteService ownedRecipientQuoteService,
            HyperlinkProtocolCapacityService capacityService, Clock clock) {
        this.dataPackageService = dataPackageService;
        this.taskMapper = taskMapper;
        this.walletPort = walletPort;
        this.tokenService = tokenService;
        this.ownedRecipientQuoteService = ownedRecipientQuoteService;
        this.capacityService = capacityService;
        this.clock = clock;
    }

    public HyperlinkTaskQuoteVO quote(HyperlinkTaskQuoteDTO request, AuthPrincipal principal) {
        QuoteInput input = normalize(request);
        DataPackageClaimSnapshot snapshot;
        if ("START".equals(input.purpose())) {
            HyperlinkTask task = taskMapper.selectById(input.taskId());
            if (task == null || task.getDataPackageId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "未开始任务尚未配置数据包");
            }
            input = new QuoteInput("START", task.getId(), task.getDataPackageId(),
                    modeApi(task.getTaskType()), task.getConcurrentNum(), task.getMaxUseAccount(),
                    task.getVersion());
            snapshot = ownedRecipientQuoteService.snapshot(task)
                    .orElseGet(() -> dataPackageService.snapshot(task.getDataPackageId()));
        } else {
            snapshot = dataPackageService.snapshot(input.dataPackageId());
        }
        IssuedQuote issued = issue(input, principal.tenantId(), principal.userId(), snapshot);
        return withToken(issued);
    }

    /** 后台重建按明确租户/操作者/冻结配置重新生成报价事实，不伪造登录身份。 */
    public HyperlinkQuoteTokenService.QuoteClaims quoteInternal(InternalQuoteInput request) {
        if (request == null || request.tenantId() <= 0 || request.userId() <= 0
                || request.dataPackageId() <= 0 || request.maxExecutingAccounts() < 0
                || request.maxExecutingAccounts()
                    > HyperlinkTaskConfigurationFactory.MAX_EXECUTING_ACCOUNTS
                || request.maxUseAccounts() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "内部报价参数非法");
        }
        HyperlinkTaskMode.fromApi(request.taskMode());
        QuoteInput input = new QuoteInput("CREATE", null, request.dataPackageId(), request.taskMode(),
                request.maxExecutingAccounts(), request.maxUseAccounts(), null);
        return issue(input, request.tenantId(), request.userId(),
                dataPackageService.snapshot(request.dataPackageId())).claims();
    }

    private IssuedQuote issue(QuoteInput input, long tenantId, long userId,
            DataPackageClaimSnapshot snapshot) {
        List<HyperlinkRecipientCountryCount> counts = snapshot.countryCounts().stream()
                .map(row -> new HyperlinkRecipientCountryCount(row.countryIso2(), row.recipientCount()))
                .toList();
        int recipientCount = snapshot.recipientCount();
        capacityService.requireSufficient(input.maxExecutingAccounts());
        int pricingConcurrency = input.maxExecutingAccounts() == 0
                ? capacityService.resolveAutoLimit(input.maxUseAccounts())
                : input.maxExecutingAccounts();
        HyperlinkWalletPort.PricingSnapshot pricing = walletPort.quote(
                tenantId, pricingConcurrency, counts);
        BigDecimal available = pricing.accountBalance().add(pricing.giftBalance());
        List<HyperlinkTaskQuoteBreakdownVO> breakdown = pricing.breakdown().stream()
                .map(row -> new HyperlinkTaskQuoteBreakdownVO(row.countryIso2(), row.recipientCount(),
                        row.unitPrice(), row.amount()))
                .toList();
        long expiresAt = clock.millis() + QUOTE_TTL_MS;
        String quoteId = UUID.randomUUID().toString();
        HyperlinkTaskQuoteVO unsigned = new HyperlinkTaskQuoteVO("", expiresAt, snapshot.dataPackageId(),
                snapshot.generation(), snapshot.packageName(), recipientCount,
                input.maxExecutingAccounts(), pricingConcurrency,
                pricing.pricingMode(), pricing.priceCode(), pricing.currencyCode(), pricing.unitPrice(),
                breakdown, pricing.estimatedAmount(), pricing.accountBalance(), pricing.giftBalance(), available);
        HyperlinkQuoteTokenService.QuoteClaims claims = new HyperlinkQuoteTokenService.QuoteClaims(
                quoteId, tenantId, userId, input.purpose(), input.taskId(),
                input.taskVersion(), snapshot.dataPackageId(), snapshot.generation(), snapshot.upperPhoneId(),
                input.taskMode(), input.maxExecutingAccounts(), input.maxUseAccounts(),
                pricing.provider(), expiresAt, unsigned);
        return new IssuedQuote(claims, unsigned);
    }

    private HyperlinkTaskQuoteVO withToken(IssuedQuote issued) {
        HyperlinkTaskQuoteVO quote = issued.quote();
        return new HyperlinkTaskQuoteVO(tokenService.sign(issued.claims()), quote.expiresAt(), quote.dataPackageId(),
                quote.dataPackageGeneration(), quote.dataPackageName(), quote.recipientCount(),
                quote.configuredMaxExecutingAccounts(), quote.effectiveMaxExecutingAccounts(),
                quote.pricingMode(), quote.priceCode(), quote.currencyCode(), quote.unitPrice(),
                quote.pricingBreakdown(), quote.estimatedAmount(), quote.accountBalance(),
                quote.giftBalance(), quote.availableBalance());
    }

    private QuoteInput normalize(HyperlinkTaskQuoteDTO request) {
        if (request == null || request.purpose() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "purpose 必填");
        }
        if ("CREATE".equals(request.purpose())) {
            if (request.taskId() != null || request.dataPackageId() == null
                    || request.taskMode() == null || request.maxExecutingAccounts() == null
                    || request.maxUseAccounts() == null || request.maxUseAccounts() < 0
                    || request.maxExecutingAccounts() < 0
                    || request.maxExecutingAccounts()
                        > HyperlinkTaskConfigurationFactory.MAX_EXECUTING_ACCOUNTS) {
                throw new BusinessException(ErrorCode.VALIDATION, "CREATE 报价字段不完整或混入 START 字段");
            }
            HyperlinkTaskMode.fromApi(request.taskMode());
            return new QuoteInput("CREATE", null, request.dataPackageId(), request.taskMode(),
                    request.maxExecutingAccounts(), request.maxUseAccounts(), null);
        }
        if ("START".equals(request.purpose()) && request.taskId() != null
                && request.dataPackageId() == null && request.taskMode() == null
                && request.maxExecutingAccounts() == null && request.maxUseAccounts() == null) {
            return new QuoteInput("START", request.taskId(), null, null, 0, 0, null);
        }
        throw new BusinessException(ErrorCode.VALIDATION, "报价请求 purpose 或互斥字段非法");
    }

    private String modeApi(Integer code) {
        return switch (code) {
            case 1 -> "instant";
            case 2 -> "rolling";
            case 3 -> "cycle";
            default -> throw new BusinessException(ErrorCode.VALIDATION, "任务模式非法");
        };
    }

    private record QuoteInput(String purpose, Long taskId, Long dataPackageId,
                              String taskMode, int maxExecutingAccounts, int maxUseAccounts,
                              Integer taskVersion) { }

    /** 后台重建使用的最小且显式的报价归属与冻结参数。 */
    public record InternalQuoteInput(long tenantId, long userId, long dataPackageId,
            String taskMode, int maxExecutingAccounts, int maxUseAccounts) { }

    private record IssuedQuote(HyperlinkQuoteTokenService.QuoteClaims claims,
            HyperlinkTaskQuoteVO quote) { }
}
