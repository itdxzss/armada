package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.vo.AccountGroupOptionVO;
import com.armada.account.service.AccountGroupService;
import com.armada.hyperlink.data.service.DataPackageService;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskDetailRow;
import com.armada.hyperlink.task.port.HyperlinkWalletPort;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.model.vo.CountryOptionsVO;
import com.armada.platform.country.service.CountryService;
import com.armada.promotion.channel.model.vo.PromotionChannelOptionVO;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** H2 查询合同的完整回填、租户边界、真实上下文和试算委托测试。 */
class HyperlinkTaskQueryServiceTest {

    private static final long NOW = 2_000_000_000_000L;

    private final HyperlinkTaskMapper taskMapper = mock(HyperlinkTaskMapper.class);
    private final DataPackageService dataPackageService = mock(DataPackageService.class);
    private final HyperlinkWalletPort walletPort = mock(HyperlinkWalletPort.class);
    private final HyperlinkAccountCandidateSelector accountSelector =
            mock(HyperlinkAccountCandidateSelector.class);
    private final AccountGroupService accountGroupService = mock(AccountGroupService.class);
    private final CountryService countryService = mock(CountryService.class);
    private final PromotionChannelService promotionChannelService =
            mock(PromotionChannelService.class);
    private final HyperlinkTaskQueryService service = new HyperlinkTaskQueryService(
            taskMapper, dataPackageService, walletPort, accountSelector, accountGroupService,
            countryService, promotionChannelService, new ObjectMapper(),
            new HyperlinkAccountFilterNormalizer(),
            Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void detailRefillsEveryHistoricalDoubleImageAndFilterFactWithStalePackagePlaceholder() {
        when(taskMapper.selectDetailById(7L, 11L)).thenReturn(detailRow());
        when(dataPackageService.detail(31L)).thenThrow(
                new BusinessException(ErrorCode.NOT_FOUND, "数据包不存在"));

        var result = service.detail(11L);

        assertThat(result.id()).isEqualTo(11L);
        assertThat(result.messageType()).isEqualTo(2);
        assertThat(result.editable()).isTrue();
        assertThat(result.messageContent().linkPreviewAssetId()).isEqualTo(55L);
        assertThat(result.messageContent().bodyMainAssetId()).isEqualTo(66L);
        assertThat(result.messageContent().promotionLink()).isEqualTo("https://example.com");
        assertThat(result.accountFilter().rotationStatus()).isEqualTo(2);
        assertThat(result.accountFilter().countryIso2s()).containsExactly("BR");
        assertThat(result.accountFilter().groupIds()).containsExactly(9L);
        assertThat(result.accountFilter().groupInviteAllowed()).isTrue();
        assertThat(result.accountFilter().source()).isEqualTo(3);
        assertThat(result.accountFilter().friendCountMin()).isEqualTo(10);
        assertThat(result.accountFilter().registerDaysMax()).isEqualTo(180);
        assertThat(result.messageIntervalMinSeconds()).isEqualByComparingTo("0.5");
        assertThat(result.messageIntervalMaxSeconds()).isEqualByComparingTo("0.7");
        assertThat(result.dataPackageName()).isEqualTo("历史数据包");
        assertThat(result.dataPackageAvailable()).isFalse();
    }

    @Test
    void detailFailsClosedWhenHistoricalAccountFilterSnapshotIsInvalid() {
        HyperlinkTaskDetailRow base = detailRow();
        HyperlinkTaskDetailRow invalid = new HyperlinkTaskDetailRow(
                base.id(), base.taskName(), base.taskType(), base.startMode(),
                base.taskDelayMinutes(), base.taskPlannedEndAt(), base.taskIntervalMinutes(),
                base.dataPackageId(), base.dataPackageNameSnapshot(),
                filterJson().replace("\"rotationStatus\":2", "\"rotationStatus\":4"),
                base.maxUseAccount(), base.concurrentNum(), base.accountMaxSendNum(),
                base.msgIntervalMinMs(), base.msgIntervalMaxMs(), base.shortLinkEnabled(),
                base.version(), base.createdAt(), base.updatedAt(), base.messageSchemaVersion(),
                base.messageType(), base.title(), base.content(), base.linkDescription(),
                base.promotionLink(), base.buttons(), base.cardText(), base.linkPreviewAssetId(),
                base.bodyMainAssetId(), base.enabled(), base.runStatus(), base.provisionStatus());
        when(taskMapper.selectDetailById(7L, 11L)).thenReturn(invalid);

        assertThatThrownBy(() -> service.detail(11L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("rotationStatus");
    }

    @Test
    void detailUsesExplicitTenantAndHidesMissingOrForeignTaskAsNotFound() {
        when(taskMapper.selectDetailById(7L, 99L)).thenReturn(null);

        assertThatThrownBy(() -> service.detail(99L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(40401));
        verify(taskMapper).selectDetailById(7L, 99L);
    }

    @Test
    void preparingTaskIsNotAdvertisedEditableEvenWhenRunStatusIsNotStarted() {
        HyperlinkTaskDetailRow base = detailRow();
        HyperlinkTaskDetailRow processing = new HyperlinkTaskDetailRow(
                base.id(), base.taskName(), base.taskType(), base.startMode(),
                base.taskDelayMinutes(), base.taskPlannedEndAt(), base.taskIntervalMinutes(),
                null, null, base.accountFilter(), base.maxUseAccount(), base.concurrentNum(),
                base.accountMaxSendNum(), base.msgIntervalMinMs(), base.msgIntervalMaxMs(),
                base.shortLinkEnabled(), base.version(), base.createdAt(), base.updatedAt(),
                base.messageSchemaVersion(), base.messageType(), base.title(), base.content(),
                base.linkDescription(), base.promotionLink(), base.buttons(), base.cardText(),
                base.linkPreviewAssetId(), base.bodyMainAssetId(), true, 0, 1);
        when(taskMapper.selectDetailById(7L, 11L)).thenReturn(processing);

        assertThat(service.detail(11L).editable()).isFalse();
    }

    @Test
    void createContextReturnsRealPricingAndTenantWidePrivateProtocolCapacity() {
        when(accountSelector.protocolCount()).thenReturn(4);
        when(accountSelector.protocolIds()).thenReturn(List.of("ANDROID", "WEB"));
        when(accountGroupService.options()).thenReturn(List.of(
                new AccountGroupOptionVO(9L, "公共组")));
        when(countryService.options("marketing-export")).thenReturn(new CountryOptionsVO(List.of(
                new CountryOptionVO("TH", "TH", "泰国", "Thailand", "+66", "🇹🇭",
                        false, "ASIA"),
                new CountryOptionVO("BR", "BR", "巴西", "Brazil", "+55", "🇧🇷",
                        false, "SOUTH_AMERICA"))));
        when(promotionChannelService.options()).thenReturn(List.of(
                new PromotionChannelOptionVO(20L, "渠道 A")));
        when(walletPort.quote(7L, 1, List.of())).thenReturn(
                new HyperlinkWalletPort.PricingSnapshot(
                        "wallet", "NORMAL", "hyperlink_task", "USDT",
                        new BigDecimal("0.02"), List.of(), BigDecimal.ZERO,
                        new BigDecimal("100.00"), new BigDecimal("5.00")));

        var result = service.createContext();

        assertThat(result.protocolCount()).isEqualTo(4);
        assertThat(result.maxConcurrentNum()).isEqualTo(60);
        assertThat(result.accountSendConcurrency()).isEqualTo(20);
        assertThat(result.defaultSubTaskNum()).isEqualTo(50);
        assertThat(result.availableBalance()).isEqualByComparingTo("105.00");
        assertThat(result.referenceUnitPrice()).isEqualByComparingTo("0.02");
        assertThat(result.defaultAccountGroupIds()).isEmpty();
        assertThat(result.groupOptions()).containsExactly(
                new com.armada.hyperlink.task.model.vo.HyperlinkIdOptionVO(9L, "公共组"));
        assertThat(result.countryOptions()).extracting(option -> option.value())
                .containsExactly("BR", "TH");
        assertThat(result.countryOptions().get(0).flag()).isEqualTo("🇧🇷");
        assertThat(result.channelOptions()).extracting(option -> option.value())
                .containsExactly(20L);
        assertThat(result.protocolOptions()).extracting(option -> option.value())
                .containsExactly("ANDROID", "WEB");
    }

    @Test
    void accountMatchDelegatesExactFilterCountButKeepsProtocolCapacityTenantWide() {
        HyperlinkAccountFilterDTO filter = filter();
        when(accountSelector.count(filter, NOW)).thenReturn(17);
        when(accountSelector.protocolCount()).thenReturn(4);

        var result = service.accountMatchCount(filter);

        assertThat(result.availableAccountCount()).isEqualTo(17);
        assertThat(result.protocolCount()).isEqualTo(4);
        assertThat(result.maxConcurrentNum()).isEqualTo(60);
        verify(accountSelector).count(filter, NOW);
    }

    private HyperlinkTaskDetailRow detailRow() {
        return new HyperlinkTaskDetailRow(
                11L, "历史双图文", 2, 2, 15, NOW + 60_000L, 0,
                31L, "历史数据包", filterJson(), 5, 3, 100, 500, 700,
                false, 4, NOW - 1_000L, NOW, 1, 2, "标题", "正文",
                "描述", "https://example.com", "[]", null, 55L, 66L,
                false, 0, 0);
    }

    private HyperlinkAccountFilterDTO filter() {
        return new HyperlinkAccountFilterDTO(
                1, List.of("BR"), List.of(), null, List.of(9L), List.of(),
                "WEB", "ONLINE", 4, 2, null, "web5", "full_param", true,
                null, null, 3, 10, 20, null, null, 90, 180, null, null);
    }

    private String filterJson() {
        return """
                {"filterSchemaVersion":1,"countryIso2s":[" br ","BR"],"groupIds":[9,9],
                 "protocolId":"WEB","onlineStatus":"ONLINE","rotationStatus":2,
                 "accountType":2,"widType":"web5","importMode":"full_param",
                 "groupInviteAllowed":true,"source":3,"friendCountMin":10,
                 "friendCountMax":20,"registerDaysMin":90,"registerDaysMax":180}
                """;
    }
}
