package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskButtonDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskMessageContentDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskSaveDTO;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskMode;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskStartMode;
import com.armada.hyperlink.template.service.HyperlinkMessageContentValidator;
import com.armada.marketing.service.MarketingTemplateFileService;
import com.armada.shared.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 超链任务筛选白名单、字段归一化和模式字段清理测试。 */
class HyperlinkTaskConfigurationFactoryTest {

    private static final long NOW = 2_000_000_000_000L;

    private final HyperlinkTaskConfigurationFactory factory = new HyperlinkTaskConfigurationFactory(
            new HyperlinkMessageContentValidator(mock(MarketingTemplateFileService.class)),
            new ObjectMapper(), new HyperlinkAccountFilterNormalizer(),
            Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

    @Test
    void normalizesSupportedFilterAndClearsFieldsOutsideInstantNowModes() {
        HyperlinkAccountFilterDTO filter = filter(
                List.of("br", " CN ", "br"), List.of(), " south_america ",
                List.of(9L, 3L, 9L), List.of(8L, 2L, 8L));

        var normalized = factory.normalizeForCreate(request("instant", NOW + 120_000L, 30,
                "now", 15, filter));

        assertThat(normalized.taskMode()).isEqualTo(HyperlinkTaskMode.INSTANT);
        assertThat(normalized.startMode()).isEqualTo(HyperlinkTaskStartMode.NOW);
        assertThat(normalized.plannedEndAt()).isNull();
        assertThat(normalized.cycleIntervalMinutes()).isZero();
        assertThat(normalized.delayMinutes()).isZero();
        assertThat(normalized.accountFilter().countryIso2s()).containsExactly("BR", "CN");
        assertThat(normalized.accountFilter().groupIds()).containsExactly(3L, 9L);
        assertThat(normalized.accountFilter().channelIds()).containsExactly(2L, 8L);
        assertThat(normalized.accountFilter().continent()).isEqualTo("SOUTH_AMERICA");
        assertThat(normalized.accountFilter().protocolId()).isEqualTo("WEB");
        assertThat(normalized.accountFilter().onlineStatus()).isEqualTo("ONLINE");
        assertThat(normalized.accountFilter().widType()).isEqualTo("web5");
        assertThat(normalized.accountFilter().retentionDaysMin()).isNull();
    }

    @Test
    void normalizesEveryFilterBackedByAccountProfile() {
        HyperlinkAccountFilterDTO profileFilter = new HyperlinkAccountFilterDTO(
                1, List.of(), List.of(), null, List.of(), List.of(),
                null, null, 1, null, null, null,
                null, true, null, null, 4, 10,
                20, null, null, null, null, 90,
                180, null, null);

        var normalized = factory.normalizeForCreate(request(
                "instant", null, 0, "now", 0, profileFilter)).accountFilter();

        assertThat(normalized.rotationStatus()).isEqualTo(1);
        assertThat(normalized.groupInviteAllowed()).isTrue();
        assertThat(normalized.source()).isEqualTo(4);
        assertThat(normalized.friendCountMin()).isEqualTo(10);
        assertThat(normalized.friendCountMax()).isEqualTo(20);
        assertThat(normalized.registerDaysMin()).isEqualTo(90);
        assertThat(normalized.registerDaysMax()).isEqualTo(180);
    }

    @Test
    void acceptsDistinctCountryInclusionsAndExclusionsButRejectsOverlap() {
        HyperlinkAccountFilterDTO bothCountries = filter(
                List.of("BR"), List.of("CN"), null, List.of(), List.of());
        var normalized = factory.normalizeForCreate(request(
                "instant", null, 0, "now", 0, bothCountries));
        assertThat(normalized.accountFilter().countryIso2s()).containsExactly("BR");
        assertThat(normalized.accountFilter().excludeCountryIso2s()).containsExactly("CN");

        HyperlinkAccountFilterDTO overlap = filter(
                List.of("BR"), List.of("BR"), null, List.of(), List.of());
        assertThatThrownBy(() -> factory.normalizeForCreate(request(
                "instant", null, 0, "now", 0, overlap)))
                .hasMessageContaining("不能重复");
    }

    @Test
    void rejectsInvalidRangesAndStaleRollingEnd() {

        HyperlinkAccountFilterDTO invalidRange = new HyperlinkAccountFilterDTO(
                1, List.of(), List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, BigDecimal.TEN, BigDecimal.ONE, null,
                null, null, null);
        assertThatThrownBy(() -> factory.normalizeForCreate(request(
                "instant", null, 0, "now", 0, invalidRange)))
                .hasMessageContaining("retentionDays 最小值不能大于最大值");

        assertThatThrownBy(() -> factory.normalizeForCreate(request(
                "rolling", NOW + 59_999L, 60, "now", 0, emptyFilter())))
                .hasMessageContaining("至少晚于当前 1 分钟");
    }

    @Test
    void rejectsNullIdsAndNonPositiveRegisterDaysInsteadOfSilentlyDroppingThem() {
        HyperlinkAccountFilterDTO nullId = filter(
                List.of(), List.of(), null, java.util.Arrays.asList(1L, null), List.of());
        assertThatThrownBy(() -> factory.normalizeForCreate(request(
                "instant", null, 0, "now", 0, nullId)))
                .hasMessageContaining("groupIds 元素必须大于 0");

        HyperlinkAccountFilterDTO zeroRegisterDays = new HyperlinkAccountFilterDTO(
                1, List.of(), List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, 0,
                null, null, null);
        assertThatThrownBy(() -> factory.normalizeForCreate(request(
                "instant", null, 0, "now", 0, zeroRegisterDays)))
                .hasMessageContaining("registerDaysMin 必须大于 0");
    }

    @Test
    void historicalDoubleImageCanEditContentOnlyWhenExistingTypeIsAlsoTwo() {
        HyperlinkTaskSaveDTO request = doubleImageRequest();

        assertThatThrownBy(() -> factory.normalizeForCreate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂不支持双图文");

        var normalized = factory.normalizeForUpdate(request, 2);
        assertThat(normalized.content().messageType()).isEqualTo(2);
        assertThat(normalized.content().title()).isEqualTo("修改后的双图标题");
        assertThat(normalized.content().linkDescription()).isEqualTo("修改后的描述");
        assertThat(normalized.content().promotionLink()).isEqualTo("https://example.com/double");
        assertThat(normalized.content().buttons()).isEmpty();

        assertThatThrownBy(() -> factory.normalizeForUpdate(request, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能修改消息类型");
        assertThatThrownBy(() -> factory.normalizeForUpdate(
                request("instant", null, 0, "now", 0, emptyFilter()), 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能修改消息类型");
    }

    @Test
    void rejectsInvalidProfileEnumsAndRanges() {
        HyperlinkAccountFilterDTO invalidRotation = new HyperlinkAccountFilterDTO(
                1, List.of(), List.of(), null, List.of(), List.of(),
                null, null, 4, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null);
        assertThatThrownBy(() -> factory.normalizeForCreate(request(
                "instant", null, 0, "now", 0, invalidRotation)))
                .hasMessageContaining("rotationStatus 非法");

        HyperlinkAccountFilterDTO invalidFriendRange = new HyperlinkAccountFilterDTO(
                1, List.of(), List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null,
                null, null, null, null, null, 20,
                10, null, null, null, null, null,
                null, null, null);
        assertThatThrownBy(() -> factory.normalizeForCreate(request(
                "instant", null, 0, "now", 0, invalidFriendRange)))
                .hasMessageContaining("friendCount 最小值不能大于最大值");

        HyperlinkAccountFilterDTO invalidSource = new HyperlinkAccountFilterDTO(
                1, List.of(), List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null,
                null, null, null, null, 5, null,
                null, null, null, null, null, null,
                null, null, null);
        assertThatThrownBy(() -> factory.normalizeForCreate(request(
                "instant", null, 0, "now", 0, invalidSource)))
                .hasMessageContaining("source 非法");
    }

    private HyperlinkAccountFilterDTO filter(List<String> countries, List<String> excludes,
            String continent, List<Long> groups, List<Long> channels) {
        return new HyperlinkAccountFilterDTO(
                1, countries, excludes, continent, groups, channels,
                " web ", " online ", null, 2, " android_business_companion ", " WEB5 ",
                " FULL_PARAM ", null, " 5512 ", 30L, null, null,
                null, null, null, BigDecimal.ZERO, BigDecimal.valueOf(10.5), null,
                null, NOW - 10_000L, NOW + 10_000L);
    }

    private HyperlinkAccountFilterDTO emptyFilter() {
        return new HyperlinkAccountFilterDTO(
                1, List.of(), List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null);
    }

    private HyperlinkTaskSaveDTO request(String mode, Long plannedEndAt, int cycleMinutes,
            String startMode, int delayMinutes, HyperlinkAccountFilterDTO filter) {
        return new HyperlinkTaskSaveDTO(null, null, "筛选任务", 3,
                new HyperlinkTaskMessageContentDTO(null, "标题", null, null, null, "正文", null,
                        List.of(new HyperlinkTaskButtonDTO(
                                "CTA_URL", "查看", "https://example.com", false))),
                mode, plannedEndAt, cycleMinutes, filter,
                BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.7), 1, 1, 0,
                startMode, delayMinutes, null, false, null);
    }

    private HyperlinkTaskSaveDTO doubleImageRequest() {
        return new HyperlinkTaskSaveDTO(3, null, "历史双图文", 2,
                new HyperlinkTaskMessageContentDTO(null, "修改后的双图标题", "修改后的描述",
                        "https://example.com/double", null, "修改后的正文", null, List.of()),
                "instant", null, 0, emptyFilter(), BigDecimal.valueOf(0.5),
                BigDecimal.valueOf(0.7), 1, 1, 0, "now", 0, null, false, null);
    }
}
