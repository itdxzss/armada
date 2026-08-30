package com.armada.hyperlink.strategy.service;

import com.armada.account.service.AccountGroupService;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyAccountContextVO;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.vo.HyperlinkAccountMatchCountVO;
import com.armada.hyperlink.task.model.vo.HyperlinkCountryOptionVO;
import com.armada.hyperlink.task.model.vo.HyperlinkIdOptionVO;
import com.armada.hyperlink.task.model.vo.HyperlinkStringOptionVO;
import com.armada.hyperlink.task.service.HyperlinkAccountCandidateSelector;
import com.armada.hyperlink.task.service.HyperlinkTaskQueryService;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.service.CountryService;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/** 为策略筛选表单提供不触发钱包报价的账号选项与实时试算。 */
@Service
public class HyperlinkStrategyAccountContextService {

    private final AccountGroupService accountGroupService;
    private final CountryService countryService;
    private final PromotionChannelService promotionChannelService;
    private final HyperlinkAccountCandidateSelector accountSelector;
    private final HyperlinkTaskQueryService taskQueryService;

    public HyperlinkStrategyAccountContextService(
            AccountGroupService accountGroupService,
            CountryService countryService,
            PromotionChannelService promotionChannelService,
            HyperlinkAccountCandidateSelector accountSelector,
            HyperlinkTaskQueryService taskQueryService) {
        this.accountGroupService = accountGroupService;
        this.countryService = countryService;
        this.promotionChannelService = promotionChannelService;
        this.accountSelector = accountSelector;
        this.taskQueryService = taskQueryService;
    }

    /** 返回筛选抽屉所需的真实只读选项，不访问钱包端口。 */
    public HyperlinkStrategyAccountContextVO context() {
        requireTenant();
        List<Long> defaultGroupIds = accountGroupService.hyperlinkDefaultGroupIds();
        List<HyperlinkIdOptionVO> groups = accountGroupService.options().stream()
                .map(option -> new HyperlinkIdOptionVO(option.id(), option.name()))
                .toList();
        List<HyperlinkCountryOptionVO> countries = countryService
                .options("marketing-export").rows().stream()
                .map(option -> new HyperlinkCountryOptionVO(
                        option.value(), countryLabel(option), option.flag(), option.continentCode()))
                .sorted(Comparator.comparing(HyperlinkCountryOptionVO::value))
                .toList();
        List<HyperlinkIdOptionVO> channels = promotionChannelService.options().stream()
                .map(option -> new HyperlinkIdOptionVO(option.id(), option.name()))
                .toList();
        List<HyperlinkStringOptionVO> protocols = accountSelector.protocolIds().stream()
                .map(value -> new HyperlinkStringOptionVO(value, value))
                .toList();
        return new HyperlinkStrategyAccountContextVO(
                defaultGroupIds,
                groups, countries, channels, protocols);
    }

    /** 复用任务运行选号的同一归一化、SQL 和 PRIVATE 能力门禁做实时试算。 */
    public HyperlinkAccountMatchCountVO matchCount(HyperlinkAccountFilterDTO filter) {
        requireTenant();
        return taskQueryService.accountMatchCount(filter);
    }

    private static String countryLabel(CountryOptionVO option) {
        if (option.nameZh() != null && !option.nameZh().isBlank()) {
            return option.nameZh();
        }
        if (option.nameEn() != null && !option.nameEn().isBlank()) {
            return option.nameEn();
        }
        return option.value();
    }

    private static void requireTenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId < 1) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
    }
}
