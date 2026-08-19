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
    void writeCreatorPersistsStrictCountryAndContinentResult() {
        String phone = "5215512345678";
        CountryReferenceVO mexico = new CountryReferenceVO(
                135L, "MX", "墨西哥", "+52", "🇲🇽", "NORTH_AMERICA");
        when(countryService.resolveActiveCountriesByPhoneNumbers(List.of(phone)))
                .thenReturn(Map.of(phone, mexico));

        writer.writeCreator(77L, phone, 1_787_096_047_000L);

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
