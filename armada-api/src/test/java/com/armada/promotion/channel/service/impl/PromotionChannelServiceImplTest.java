package com.armada.promotion.channel.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.country.model.vo.CountryReferenceVO;
import com.armada.platform.country.service.CountryService;
import com.armada.promotion.channel.converter.PromotionChannelConverter;
import com.armada.promotion.channel.mapper.PromotionChannelMapper;
import com.armada.promotion.channel.model.dto.PromotionChannelCreateDTO;
import com.armada.promotion.channel.model.dto.PromotionChannelQuery;
import com.armada.promotion.channel.model.entity.PromotionChannel;
import com.armada.promotion.channel.model.entity.PromotionChannelTrackingConfig;
import com.armada.promotion.channel.model.entity.PromotionDomain;
import com.armada.promotion.channel.model.entity.PromotionLandingTemplate;
import com.armada.promotion.channel.model.vo.PromotionChannelVoRow;
import com.armada.promotion.channel.security.PromotionTokenCipher;
import com.armada.promotion.channel.support.ChannelCodeGenerator;
import com.armada.shared.exception.BusinessException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
        CountryReferenceVO target = country(101L, "IN", "印度", "+91");
        when(mapper.selectAvailableTemplateById(11L)).thenReturn(template);
        when(countryService.requireActiveReference(101L)).thenReturn(target);
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

        var result = service.create(request(11L, 101L, 101L, "https://GO.example.com", 1,
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
    void createReusesSameTemplateDomainButRejectsCrossTemplateDomain() {
        when(mapper.selectAvailableTemplateById(11L)).thenReturn(template(11L, "模板A"));
        when(countryService.requireActiveReference(101L)).thenReturn(country(101L, "IN", "印度", "+91"));
        PromotionDomain occupied = new PromotionDomain();
        occupied.setId(31L);
        occupied.setDomainHost("go.example.com");
        occupied.setLandingTemplateId(12L);
        when(mapper.selectActiveDomainByHost("go.example.com")).thenReturn(occupied);

        assertThatThrownBy(() -> service.create(
                request(11L, 101L, 101L, "go.example.com", 1, null, null)))
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
        when(countryService.referencesByIds(List.of(101L, 102L))).thenReturn(Map.of(
                101L, country(101L, "IN", "印度", "+91"),
                102L, country(102L, "BR", "巴西", "+55")));

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

    private static PromotionChannelCreateDTO request(
            Long templateId,
            Long targetCountryId,
            Long preselectedCountryId,
            String domain,
            Integer platform,
            String trackingId,
            String token) {
        return new PromotionChannelCreateDTO(
                "印度渠道", 20001L, targetCountryId, templateId, domain,
                preselectedCountryId, platform, trackingId, token,
                "Lead", "InitiateCheckout", "CompleteRegistration", true, true);
    }

    private static PromotionLandingTemplate template(Long id, String name) {
        PromotionLandingTemplate row = new PromotionLandingTemplate();
        row.setId(id);
        row.setTemplateName(name);
        return row;
    }

    private static CountryReferenceVO country(Long id, String iso2, String name, String prefix) {
        return new CountryReferenceVO(id, iso2, name, prefix, "flag");
    }

    private static PromotionChannelVoRow row() {
        PromotionChannelVoRow row = new PromotionChannelVoRow();
        row.setId(51L);
        row.setChannelCode("a8k2m9qx");
        row.setChannelName("印度渠道");
        row.setOwnerUserId(20001L);
        row.setTargetCountryId(101L);
        row.setPreselectedCountryId(102L);
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
}
