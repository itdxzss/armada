package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.platform.country.model.vo.CountryReferenceVO;
import com.armada.platform.country.service.CountryService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupCreatorCompatibilityWriterTest {

    @Mock
    private GroupLinkPreviewMapper previewMapper;

    @Mock
    private CountryService countryService;

    @InjectMocks
    private GroupCreatorCompatibilityWriter writer;

    @Test
    void sampleJidUsesRealInternationalPhoneValidationAndCountryProjection() {
        var countryMapper = org.mockito.Mockito.mock(com.armada.platform.country.mapper.CountryMapper.class);
        var india = new com.armada.platform.country.model.entity.Country();
        india.setId(1L);
        india.setIso2("IN");
        india.setNameZh("印度");
        india.setPhonePrefix("+91");
        india.setContinentCode("ASIA");
        when(countryMapper.selectActive()).thenReturn(List.of(india));
        var resolver = new GroupCreatorCompatibilityWriter(previewMapper,
                new com.armada.platform.country.service.impl.CountryServiceImpl(countryMapper));
        resolver.writeCreator(77L, "916375552817-1517537054@g.us", null, 100L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupLinkPreview>> rows = ArgumentCaptor.forClass(List.class);
        verify(previewMapper).upsertCreatorCompatibility(rows.capture());
        assertThat(rows.getValue().get(0).getOwnerPhone()).isEqualTo("916375552817");
        assertThat(rows.getValue().get(0).getCreatorCountryIso2()).isEqualTo("IN");
        assertThat(rows.getValue().get(0).getCreatorContinentCode()).isEqualTo("ASIA");
    }

    @Test
    void explicitPhoneWinsOverJidAndWhitespaceDoesNotBlockFallback() {
        writer.writeCreator(77L, "916375552817-1517537054@g.us", "2348083697499@s.whatsapp.net", 100L);
        writer.writeCreator(78L, " 916375552817-1517537054@g.us ", "  ", 100L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupLinkPreview>> rows = ArgumentCaptor.forClass(List.class);
        verify(previewMapper, org.mockito.Mockito.times(2)).upsertCreatorCompatibility(rows.capture());
        assertThat(rows.getAllValues().get(0).get(0).getOwnerPhone()).isEqualTo("2348083697499");
        assertThat(rows.getAllValues().get(0).get(0).getCreatorPhoneSource()).isEqualTo(2);
        assertThat(rows.getAllValues().get(1).get(0).getOwnerPhone()).isEqualTo("916375552817");
        assertThat(rows.getAllValues().get(1).get(0).getCreatorPhoneSource()).isEqualTo(1);
    }

    @Test
    void rejectsModernAndMalformedGroupJids() {
        for (String jid : new String[]{null, "", "120363001@g.us", "123-abc@g.us", "abc-123@g.us",
                "123-456-789@g.us", "123-456@lid", "123-456@s.whatsapp.net", "123-456@g.us.extra"}) {
            writer.writeCreator(77L, jid, null, 100L);
        }
        org.mockito.Mockito.verifyNoInteractions(previewMapper, countryService);
    }

    @Test
    void countryServiceFailureStillPersistsDerivedPhone() {
        when(countryService.resolveActiveCountriesByPhoneNumbers(List.of("916375552817")))
                .thenThrow(new IllegalStateException("country unavailable"));
        writer.writeCreator(77L, "916375552817-1517537054@g.us", null, 100L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupLinkPreview>> rows = ArgumentCaptor.forClass(List.class);
        verify(previewMapper).upsertCreatorCompatibility(rows.capture());
        assertThat(rows.getValue().get(0).getOwnerPhone()).isEqualTo("916375552817");
        assertThat(rows.getValue().get(0).getCreatorCountryObserved()).isFalse();
    }

    @Test
    void legacyJidFillsMissingCreatorCountryAndContinent() {
        String phone = "916375552817";
        when(countryService.resolveActiveCountriesByPhoneNumbers(List.of(phone)))
                .thenReturn(Map.of(phone, new CountryReferenceVO(1L, "IN", "印度", "+91", "", "ASIA")));
        writer.writeCreator(77L, phone + "-1517537054@g.us", null, 100L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupLinkPreview>> rows = ArgumentCaptor.forClass(List.class);
        verify(previewMapper).upsertCreatorCompatibility(rows.capture());
        assertThat(rows.getValue().get(0).getOwnerPhone()).isEqualTo(phone);
        assertThat(rows.getValue().get(0).getCreatorPhoneSource()).isEqualTo(1);
        assertThat(rows.getValue().get(0).getCreatorCountryIso2()).isEqualTo("IN");
        assertThat(rows.getValue().get(0).getCreatorContinentCode()).isEqualTo("ASIA");
    }

    @Test
    void ignoresMissingAndNonPhoneCreatorIdentities() {
        for (String identity : new String[]{null, "", "47970506555552@lid", "2348083697499-123@g.us"}) {
            writer.writeCreator(77L, null, identity, 100L);
        }
        org.mockito.Mockito.verifyNoInteractions(previewMapper, countryService);
    }

    @Test
    void unknownCountryDoesNotAdvertiseCountryDeletion() {
        when(countryService.resolveActiveCountriesByPhoneNumbers(List.of("2348083697499")))
                .thenReturn(Map.of());
        writer.writeCreator(77L, null, "2348083697499", 100L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupLinkPreview>> rows = ArgumentCaptor.forClass(List.class);
        verify(previewMapper).upsertCreatorCompatibility(rows.capture());
        assertThat(rows.getValue().get(0).getOwnerPhoneObserved()).isTrue();
        assertThat(rows.getValue().get(0).getCreatorCountryObserved()).isFalse();
    }

    @Test
    void writeCreatorPersistsStrictCountryAndContinentResult() {
        String phone = "5215512345678";
        CountryReferenceVO mexico = new CountryReferenceVO(
                135L, "MX", "墨西哥", "+52", "🇲🇽", "NORTH_AMERICA");
        when(countryService.resolveActiveCountriesByPhoneNumbers(List.of(phone)))
                .thenReturn(Map.of(phone, mexico));

        writer.writeCreator(77L, null, phone, 1_787_096_047_000L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupLinkPreview>> rows = ArgumentCaptor.forClass(List.class);
        verify(previewMapper).upsertCreatorCompatibility(rows.capture());
        GroupLinkPreview row = rows.getValue().get(0);
        assertThat(row.getGroupLinkId()).isEqualTo(77L);
        assertThat(row.getOwnerPhone()).isEqualTo(phone);
        assertThat(row.getOwnerPhoneObserved()).isTrue();
        assertThat(row.getCreatorCountryIso2()).isEqualTo("MX");
        assertThat(row.getCreatorContinentCode()).isEqualTo("NORTH_AMERICA");
        assertThat(row.getCreatorCountryObserved()).isTrue();
    }
}
