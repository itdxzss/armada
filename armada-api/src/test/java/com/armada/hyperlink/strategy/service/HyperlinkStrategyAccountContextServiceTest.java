package com.armada.hyperlink.strategy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.model.vo.AccountGroupOptionVO;
import com.armada.account.service.AccountGroupService;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.vo.HyperlinkAccountMatchCountVO;
import com.armada.hyperlink.task.port.HyperlinkWalletPort;
import com.armada.hyperlink.task.service.HyperlinkAccountCandidateSelector;
import com.armada.hyperlink.task.service.HyperlinkTaskQueryService;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.model.vo.CountryOptionsVO;
import com.armada.platform.country.service.CountryService;
import com.armada.promotion.channel.model.vo.PromotionChannelOptionVO;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 策略账号上下文的租户边界、轻量选项与无钱包依赖合同。 */
class HyperlinkStrategyAccountContextServiceTest {

    private final AccountGroupService accountGroupService = mock(AccountGroupService.class);
    private final CountryService countryService = mock(CountryService.class);
    private final PromotionChannelService promotionChannelService =
            mock(PromotionChannelService.class);
    private final HyperlinkAccountCandidateSelector accountSelector =
            mock(HyperlinkAccountCandidateSelector.class);
    private final HyperlinkTaskQueryService taskQueryService = mock(HyperlinkTaskQueryService.class);
    private final HyperlinkStrategyAccountContextService service =
            new HyperlinkStrategyAccountContextService(
                    accountGroupService, countryService, promotionChannelService,
                    accountSelector, taskQueryService);

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void contextReturnsTenantOptionsWithoutPricingOrWalletDependency() {
        when(accountGroupService.options()).thenReturn(List.of(
                new AccountGroupOptionVO(9L, "公共组"),
                new AccountGroupOptionVO(10L, "超链组")));
        when(accountGroupService.hyperlinkDefaultGroupIds()).thenReturn(List.of(9L, 10L));
        when(countryService.options("marketing-export")).thenReturn(new CountryOptionsVO(List.of(
                new CountryOptionVO("TH", "TH", "泰国", "Thailand", "+66", "🇹🇭",
                        false, "ASIA"),
                new CountryOptionVO("BR", "BR", "", "Brazil", "+55", "🇧🇷",
                        false, "SOUTH_AMERICA"))));
        when(promotionChannelService.options()).thenReturn(List.of(
                new PromotionChannelOptionVO(20L, "渠道 A")));
        when(accountSelector.protocolIds()).thenReturn(List.of("ANDROID", "WEB"));

        var result = service.context();

        assertThat(result.defaultAccountGroupIds()).containsExactly(9L, 10L);
        assertThat(result.groupOptions()).extracting(option -> option.value())
                .containsExactly(9L, 10L);
        assertThat(result.countryOptions()).extracting(option -> option.value())
                .containsExactly("BR", "TH");
        assertThat(result.countryOptions().get(0).label()).isEqualTo("Brazil");
        assertThat(result.channelOptions()).singleElement()
                .satisfies(option -> assertThat(option.value()).isEqualTo(20L));
        assertThat(result.protocolOptions()).extracting(option -> option.value())
                .containsExactly("ANDROID", "WEB");

        assertThat(Arrays.stream(HyperlinkStrategyAccountContextService.class
                        .getConstructors()[0].getParameterTypes()))
                .noneMatch(HyperlinkWalletPort.class::isAssignableFrom);
    }

    @Test
    void matchCountDelegatesTheExactTaskQueryContract() {
        HyperlinkAccountFilterDTO filter = emptyFilter();
        HyperlinkAccountMatchCountVO expected = new HyperlinkAccountMatchCountVO(17, 4, 60);
        when(taskQueryService.accountMatchCount(filter)).thenReturn(expected);

        assertThat(service.matchCount(filter)).isSameAs(expected);
        verify(taskQueryService).accountMatchCount(filter);
    }

    @Test
    void tenantIsRequiredBeforeAnyOptionOrCountDependencyIsCalled() {
        TenantContext.clear();

        assertThatThrownBy(service::context).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.matchCount(emptyFilter()))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(accountGroupService, countryService, promotionChannelService,
                accountSelector, taskQueryService);
    }

    private static HyperlinkAccountFilterDTO emptyFilter() {
        return new HyperlinkAccountFilterDTO(
                1, List.of(), List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null);
    }
}
