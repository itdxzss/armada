package com.armada.contact.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.model.vo.AccountGroupOptionVO;
import com.armada.account.service.AccountGroupService;
import com.armada.contact.task.service.ContactAccountOptionsService;
import com.armada.promotion.channel.model.vo.PromotionChannelOptionVO;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 通讯录筛选选项保留真实 ID、区分分组与渠道，并拒绝无租户查询。 */
class ContactAccountOptionsServiceTest {

    private final AccountGroupService groups = mock(AccountGroupService.class);
    private final PromotionChannelService channels = mock(PromotionChannelService.class);
    private final ContactAccountOptionsService service = new ContactAccountOptionsService(groups, channels);

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void returnsRealNamesAndIdsWithoutMixingGroupAndChannelDimensions() {
        when(groups.options()).thenReturn(List.of(new AccountGroupOptionVO(17L, "通讯录组")));
        when(channels.options()).thenReturn(List.of(new PromotionChannelOptionVO(83L, "推广渠道")));

        var options = service.options();

        assertThat(options.groups()).containsExactly(new AccountGroupOptionVO(17L, "通讯录组"));
        assertThat(options.channels()).containsExactly(new PromotionChannelOptionVO(83L, "推广渠道"));
    }

    @Test
    void emptyTenantOptionsStayEmpty() {
        when(groups.options()).thenReturn(List.of());
        when(channels.options()).thenReturn(List.of());

        var options = service.options();

        assertThat(options.groups()).isEmpty();
        assertThat(options.channels()).isEmpty();
    }

    @Test
    void failedLookupIsNotReportedAsAnEmptyList() {
        when(groups.options()).thenThrow(new IllegalStateException("lookup unavailable"));

        assertThatThrownBy(service::options).hasMessage("lookup unavailable");
    }

    @Test
    void tenantIsRequiredBeforeEitherLookup() {
        TenantContext.clear();

        assertThatThrownBy(service::options).isInstanceOf(BusinessException.class);
        verifyNoInteractions(groups, channels);
    }
}
