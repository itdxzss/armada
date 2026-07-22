package com.armada.promotion.channel.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.service.CountryService;
import com.armada.promotion.channel.converter.PromotionChannelConverter;
import com.armada.promotion.channel.mapper.PromotionChannelMapper;
import com.armada.promotion.channel.model.dto.PromotionChannelCreateDTO;
import com.armada.promotion.channel.model.dto.PromotionChannelQuery;
import com.armada.promotion.channel.model.dto.PromotionChannelUpdateDTO;
import com.armada.promotion.channel.model.entity.PromotionChannel;
import com.armada.promotion.channel.model.entity.PromotionChannelTrackingConfig;
import com.armada.promotion.channel.model.entity.PromotionDomain;
import com.armada.promotion.channel.model.entity.PromotionLandingTemplate;
import com.armada.promotion.channel.model.vo.PromotionChannelDetailRow;
import com.armada.promotion.channel.model.vo.PromotionChannelVoRow;
import com.armada.promotion.channel.security.PromotionTokenCipher;
import com.armada.promotion.channel.support.ChannelCodeGenerator;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromotionChannelServiceImplTest {

    @Mock
    private PromotionChannelMapper mapper;

    @Mock
    private CountryService countryService;

    @Mock
    private ChannelCodeGenerator codeGenerator;

    @Mock
    private PromotionTokenCipher tokenCipher;

    private PromotionChannelServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PromotionChannelServiceImpl(
                mapper,
                countryService,
                new PromotionChannelConverter() { },
                codeGenerator,
                tokenCipher);
    }

    @Test
    void createPersistsOwnerAsCreatorAndEncryptsFacebookToken() {
        PromotionLandingTemplate template = template(11L, "基础领奖");
        CountryOptionVO target = country("IN", "印度", "+91");
        when(mapper.selectAvailableTemplateById(11L)).thenReturn(template);
        when(countryService.requireActiveOption("IN", true)).thenReturn(target);
        when(countryService.requireActiveOption("IN", false)).thenReturn(target);
        when(mapper.selectActiveDomainByHost("go.example.com")).thenReturn(null);
        when(codeGenerator.generate()).thenReturn("a8k2m9qx");
        when(tokenCipher.encrypt("secret-token")).thenReturn(
                new PromotionTokenCipher.EncryptedToken(new byte[]{1, 2, 3}, "key-v1", new byte[32]));
        doAnswer(invocation -> {
            ((PromotionDomain) invocation.getArgument(0)).setId(31L);
            return 1;
        }).when(mapper).insertDomain(any(PromotionDomain.class));
        doAnswer(invocation -> {
            ((PromotionChannel) invocation.getArgument(0)).setId(51L);
            return 1;
        }).when(mapper).insertChannel(any(PromotionChannel.class));

        var result = service.create(request(11L, "IN", "IN", "https://GO.example.com", 1,
                "pixel-123", "secret-token"));

        ArgumentCaptor<PromotionDomain> domainCaptor = ArgumentCaptor.forClass(PromotionDomain.class);
        verify(mapper).insertDomain(domainCaptor.capture());
        assertThat(domainCaptor.getValue().getDomainHost()).isEqualTo("go.example.com");
        assertThat(domainCaptor.getValue().getCreatedBy()).isEqualTo(20001L);

        ArgumentCaptor<PromotionChannel> channelCaptor = ArgumentCaptor.forClass(PromotionChannel.class);
        verify(mapper).insertChannel(channelCaptor.capture());
        assertThat(channelCaptor.getValue().getOwnerUserId()).isEqualTo(20001L);
        assertThat(channelCaptor.getValue().getCreatedBy()).isEqualTo(20001L);
        assertThat(channelCaptor.getValue().getPromotionDomainId()).isEqualTo(31L);
        assertThat(channelCaptor.getValue().getTargetCountry()).isEqualTo("IN");
        assertThat(channelCaptor.getValue().getPreselectedCountry()).isEqualTo("IN");
        assertThat(channelCaptor.getValue().getIsMarketingAllowed()).isEqualTo(1);

        ArgumentCaptor<PromotionChannelTrackingConfig> trackingCaptor =
                ArgumentCaptor.forClass(PromotionChannelTrackingConfig.class);
        verify(mapper).insertTrackingConfig(trackingCaptor.capture());
        assertThat(trackingCaptor.getValue().getAccessTokenCiphertext()).containsExactly(1, 2, 3);
        assertThat(trackingCaptor.getValue().getLeadEventName()).isEqualTo("Lead");
        assertThat(result.channelCode()).isEqualTo("a8k2m9qx");
        assertThat(result.promotionLink()).isEqualTo("https://go.example.com/a8k2m9qx");
        assertThat(result.splitLink()).isEqualTo("https://go.example.com/a8k2m9qx/1");
        assertThat(result.ownerUserId()).isEqualTo(result.creatorUserId());
    }

    @Test
    void detailReturnsAllEditableFieldsWithoutLoadingCountryOptions() {
        when(mapper.selectDetailById(51L)).thenReturn(detailRow());

        var result = service.detail(51L);

        assertThat(result.id()).isEqualTo(51L);
        assertThat(result.channelName()).isEqualTo("印度渠道");
        assertThat(result.ownerUserId()).isEqualTo(20001L);
        assertThat(result.targetCountry()).isEqualTo("IN");
        assertThat(result.landingTemplateId()).isEqualTo(11L);
        assertThat(result.domain()).isEqualTo("go.example.com");
        assertThat(result.preselectedCountry()).isEqualTo("IN");
        assertThat(result.platform()).isEqualTo(1);
        assertThat(result.trackingId()).isEqualTo("pixel-123");
        assertThat(result.accessTokenConfigured()).isTrue();
        assertThat(result.leadEventName()).isEqualTo("Lead");
        assertThat(result.loginRequestEventName()).isEqualTo("InitiateCheckout");
        assertThat(result.loginSuccessEventName()).isEqualTo("CompleteRegistration");
        assertThat(result.inAppOpenAllowed()).isTrue();
        assertThat(result.marketingAllowed()).isTrue();
        assertThat(result.status()).isEqualTo(1);
        verify(countryService, never()).optionsByValues(any());
    }

    @Test
    void detailRejectsMissingOrDeletedChannel() {
        when(mapper.selectDetailById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.detail(999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ErrorCode.NOT_FOUND.code()))
                .hasMessage("渠道不存在或已删除: 999");
    }

    @Test
    void createReusesSameTemplateDomainButRejectsCrossTemplateDomain() {
        when(mapper.selectAvailableTemplateById(11L)).thenReturn(template(11L, "模板A"));
        when(countryService.requireActiveOption("IN", true)).thenReturn(country("IN", "印度", "+91"));
        when(countryService.requireActiveOption("IN", false)).thenReturn(country("IN", "印度", "+91"));
        PromotionDomain occupied = new PromotionDomain();
        occupied.setId(31L);
        occupied.setDomainHost("go.example.com");
        occupied.setLandingTemplateId(12L);
        when(mapper.selectActiveDomainByHost("go.example.com")).thenReturn(occupied);

        assertThatThrownBy(() -> service.create(
                request(11L, "IN", "IN", "go.example.com", 1, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已绑定其他模板");

        verify(mapper, never()).insertChannel(any());
    }

    @Test
    void pageUsesOwnerIdsForReservedUpperUserFilterAndEnrichesCountriesInBatch() {
        PromotionChannelQuery query = new PromotionChannelQuery();
        query.setOwnerUserIds(List.of(20001L, 20002L));
        query.setPage(2);
        query.setPageSize(100);
        PromotionChannelVoRow row = row();
        when(mapper.countPage(query)).thenReturn(1L);
        when(mapper.selectPage(query)).thenReturn(List.of(row));
        when(countryService.optionsByValues(List.of("IN", "BR"))).thenReturn(Map.of(
                "IN", country("IN", "印度", "+91"),
                "BR", country("BR", "巴西", "+55")));

        var result = service.page(query);

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(100);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.list()).singleElement().satisfies(item -> {
            assertThat(item.creatorUserId()).isEqualTo(20001L);
            assertThat(item.targetCountryName()).isEqualTo("印度");
            assertThat(item.preselectedPhonePrefix()).isEqualTo("+55");
        });
        verify(mapper).selectPage(query);
    }

    @Test
    void updateReusesValidationAndReplacesProvidedTrackingToken() {
        when(mapper.selectActiveChannelById(51L)).thenReturn(channel(51L, 20001L));
        when(mapper.selectAvailableTemplateById(11L)).thenReturn(template(11L, "基础领奖"));
        when(countryService.requireActiveOption("IN", true)).thenReturn(country("IN", "印度", "+91"));
        when(countryService.requireActiveOption("IN", false)).thenReturn(country("IN", "印度", "+91"));
        PromotionDomain domain = new PromotionDomain();
        domain.setId(32L);
        domain.setDomainHost("go.example.com");
        domain.setLandingTemplateId(11L);
        when(mapper.selectActiveDomainByHost("go.example.com")).thenReturn(domain);
        when(tokenCipher.encrypt("new-token")).thenReturn(
                new PromotionTokenCipher.EncryptedToken(new byte[]{4, 5, 6}, "key-v2", new byte[32]));
        when(mapper.updateChannel(any(PromotionChannel.class))).thenReturn(1);
        when(mapper.updateTrackingConfig(any(PromotionChannelTrackingConfig.class))).thenReturn(1);

        service.update(51L, updateRequest(2, "pixel-new", "new-token", 0));

        ArgumentCaptor<PromotionChannel> channelCaptor = ArgumentCaptor.forClass(PromotionChannel.class);
        verify(mapper).updateChannel(channelCaptor.capture());
        assertThat(channelCaptor.getValue().getId()).isEqualTo(51L);
        assertThat(channelCaptor.getValue().getPromotionDomainId()).isEqualTo(32L);
        assertThat(channelCaptor.getValue().getStatus()).isZero();

        ArgumentCaptor<PromotionChannelTrackingConfig> trackingCaptor =
                ArgumentCaptor.forClass(PromotionChannelTrackingConfig.class);
        verify(mapper).updateTrackingConfig(trackingCaptor.capture());
        assertThat(trackingCaptor.getValue().getProviderType()).isEqualTo(2);
        assertThat(trackingCaptor.getValue().getAccessTokenCiphertext()).containsExactly(4, 5, 6);
    }

    @Test
    void updateKeepsStoredTokenWhenRequestTokenIsBlank() {
        stubUpdateReferences();
        when(mapper.countReusableTrackingToken(51L, 1, "pixel-existing")).thenReturn(1);
        when(mapper.updateChannel(any(PromotionChannel.class))).thenReturn(1);
        when(mapper.updateTrackingConfig(any(PromotionChannelTrackingConfig.class))).thenReturn(1);

        service.update(51L, updateRequest(1, "pixel-existing", " ", 1));

        ArgumentCaptor<PromotionChannelTrackingConfig> captor =
                ArgumentCaptor.forClass(PromotionChannelTrackingConfig.class);
        verify(mapper).updateTrackingConfig(captor.capture());
        assertThat(captor.getValue().getAccessTokenCiphertext()).isNull();
        verify(tokenCipher, never()).encrypt(any());
    }

    @Test
    void updateChangingCapiProviderWithoutNewTokenClearsOldProviderCredentials() {
        stubUpdateReferences();
        when(mapper.updateChannel(any(PromotionChannel.class))).thenReturn(1);
        when(mapper.updateTrackingConfig(any(PromotionChannelTrackingConfig.class))).thenReturn(1);

        service.update(51L, updateRequest(2, null, null, 1));

        InOrder order = inOrder(mapper);
        order.verify(mapper).clearTrackingCredentials(
                org.mockito.ArgumentMatchers.eq(51L),
                org.mockito.ArgumentMatchers.eq(20002L),
                org.mockito.ArgumentMatchers.anyLong());
        order.verify(mapper).updateTrackingConfig(any(PromotionChannelTrackingConfig.class));
        verify(tokenCipher, never()).encrypt(any());
    }

    @Test
    void updateChangingCapiProviderRequiresNewTokenWhenNewTrackingIdIsProvided() {
        when(mapper.selectActiveChannelById(51L)).thenReturn(channel(51L, 20001L));

        BusinessException exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> service.update(51L, updateRequest(2, "pixel-tiktok", null, 1)),
                BusinessException.class);

        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
        assertThat(exception).hasMessageContaining("切换推广平台");

        verify(mapper, never()).updateChannel(any());
        verify(mapper, never()).updateTrackingConfig(any());
        verify(mapper, never()).clearTrackingCredentials(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void updateChangingTrackingIdRequiresNewTokenEvenOnSamePlatform() {
        when(mapper.selectActiveChannelById(51L)).thenReturn(channel(51L, 20001L));
        when(mapper.countReusableTrackingToken(51L, 1, "pixel-new")).thenReturn(0);

        BusinessException exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> service.update(51L, updateRequest(1, "pixel-new", null, 1)),
                BusinessException.class);

        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
        assertThat(exception).hasMessageContaining("追踪 ID 已变化");
        verify(mapper, never()).updateChannel(any());
    }

    @Test
    void updateToNonCapiPlatformSoftDeletesTrackingConfiguration() {
        stubUpdateReferences();
        when(mapper.updateChannel(any(PromotionChannel.class))).thenReturn(1);

        service.update(51L, updateRequest(3, null, null, 1));

        verify(mapper).softDeleteTrackingConfig(
                org.mockito.ArgumentMatchers.eq(51L),
                org.mockito.ArgumentMatchers.eq(20002L),
                org.mockito.ArgumentMatchers.anyLong());
        verify(mapper, never()).updateTrackingConfig(any());
    }

    @Test
    void deleteSoftDeletesChannelBeforeTrackingInOneBusinessOperation() {
        when(mapper.selectActiveChannelById(51L)).thenReturn(channel(51L, 20001L));
        when(mapper.softDeleteChannel(
                org.mockito.ArgumentMatchers.eq(51L),
                org.mockito.ArgumentMatchers.eq(20001L),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

        service.delete(51L);

        InOrder order = inOrder(mapper);
        order.verify(mapper).softDeleteChannel(
                org.mockito.ArgumentMatchers.eq(51L),
                org.mockito.ArgumentMatchers.eq(20001L),
                org.mockito.ArgumentMatchers.anyLong());
        order.verify(mapper).softDeleteTrackingConfig(
                org.mockito.ArgumentMatchers.eq(51L),
                org.mockito.ArgumentMatchers.eq(20001L),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void deleteRejectsMissingOrAlreadyDeletedChannel() {
        when(mapper.selectActiveChannelById(51L)).thenReturn(null);

        BusinessException exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> service.delete(51L), BusinessException.class);

        assertThat(exception.getCode()).isEqualTo(ErrorCode.NOT_FOUND.code());
        assertThat(exception).hasMessageContaining("渠道不存在");

        verify(mapper, never()).softDeleteChannel(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void updateReturnsNotFoundWhenChannelIsConcurrentlyDeletedBeforeWrite() {
        stubUpdateReferences();
        when(mapper.updateChannel(any(PromotionChannel.class))).thenReturn(0);

        BusinessException exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> service.update(51L, updateRequest(1, "pixel-new", "new-token", 1)),
                BusinessException.class);

        assertThat(exception.getCode()).isEqualTo(ErrorCode.NOT_FOUND.code());
        assertThat(exception).hasMessageContaining("渠道不存在");
        verify(mapper, never()).updateTrackingConfig(any());
    }

    private static PromotionChannelCreateDTO request(
            Long templateId,
            String targetCountry,
            String preselectedCountry,
            String domain,
            Integer platform,
            String trackingId,
            String token) {
        return new PromotionChannelCreateDTO(
                "印度渠道", 20001L, targetCountry, templateId, domain,
                preselectedCountry, platform, trackingId, token,
                "Lead", "InitiateCheckout", "CompleteRegistration", true, true);
    }

    private static PromotionLandingTemplate template(Long id, String name) {
        PromotionLandingTemplate row = new PromotionLandingTemplate();
        row.setId(id);
        row.setTemplateName(name);
        return row;
    }

    private void stubUpdateReferences() {
        when(mapper.selectActiveChannelById(51L)).thenReturn(channel(51L, 20001L));
        when(mapper.selectAvailableTemplateById(11L)).thenReturn(template(11L, "基础领奖"));
        when(countryService.requireActiveOption("IN", true)).thenReturn(country("IN", "印度", "+91"));
        when(countryService.requireActiveOption("IN", false)).thenReturn(country("IN", "印度", "+91"));
        PromotionDomain domain = new PromotionDomain();
        domain.setId(31L);
        domain.setDomainHost("go.example.com");
        domain.setLandingTemplateId(11L);
        when(mapper.selectActiveDomainByHost("go.example.com")).thenReturn(domain);
    }

    private static PromotionChannelUpdateDTO updateRequest(
            Integer platform,
            String trackingId,
            String token,
            Integer status) {
        boolean capiSupported = platform != null && (platform == 1 || platform == 2);
        return new PromotionChannelUpdateDTO(
                "更新渠道", 20002L, "IN", 11L, "go.example.com", "IN",
                platform, trackingId, token,
                capiSupported ? "Lead" : null,
                capiSupported ? "InitiateCheckout" : null,
                capiSupported ? "CompleteRegistration" : null,
                true, false, status);
    }

    private static PromotionChannel channel(Long id, Long ownerUserId) {
        PromotionChannel row = new PromotionChannel();
        row.setId(id);
        row.setOwnerUserId(ownerUserId);
        row.setPlatform(1);
        return row;
    }

    private static CountryOptionVO country(String iso2, String name, String prefix) {
        return new CountryOptionVO(iso2, iso2, name, prefix, "flag", false);
    }

    private static PromotionChannelVoRow row() {
        PromotionChannelVoRow row = new PromotionChannelVoRow();
        row.setId(51L);
        row.setChannelCode("a8k2m9qx");
        row.setChannelName("印度渠道");
        row.setOwnerUserId(20001L);
        row.setTargetCountry("IN");
        row.setPreselectedCountry("BR");
        row.setLandingTemplateId(11L);
        row.setTemplateName("基础领奖");
        row.setDomainHost("go.example.com");
        row.setPlatform(1);
        row.setTrackingId("pixel-123");
        row.setStatus(1);
        row.setIsInAppOpenAllowed(1);
        row.setIsMarketingAllowed(1);
        row.setCreatedAt(1784217600000L);
        return row;
    }

    private static PromotionChannelDetailRow detailRow() {
        PromotionChannelDetailRow row = new PromotionChannelDetailRow();
        row.setId(51L);
        row.setChannelName("印度渠道");
        row.setOwnerUserId(20001L);
        row.setTargetCountry("IN");
        row.setLandingTemplateId(11L);
        row.setDomain("go.example.com");
        row.setPreselectedCountry("IN");
        row.setPlatform(1);
        row.setTrackingId("pixel-123");
        row.setAccessTokenConfigured(1);
        row.setLeadEventName("Lead");
        row.setLoginRequestEventName("InitiateCheckout");
        row.setLoginSuccessEventName("CompleteRegistration");
        row.setIsInAppOpenAllowed(1);
        row.setIsMarketingAllowed(1);
        row.setStatus(1);
        return row;
    }
}
