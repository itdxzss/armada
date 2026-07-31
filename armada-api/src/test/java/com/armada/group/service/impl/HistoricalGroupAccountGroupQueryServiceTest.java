package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.model.entity.AccountGroup;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.enums.HistoricalGroupMembershipState;
import com.armada.group.model.enums.HistoricalGroupSelfRole;
import com.armada.group.model.enums.SpeechState;
import com.armada.group.model.dto.HistoricalGroupQuery;
import com.armada.group.model.vo.HistoricalGroupAccountPhoneRow;
import com.armada.group.model.vo.HistoricalGroupPageRow;
import com.armada.platform.country.model.vo.CountryReferenceVO;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.response.PageResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HistoricalGroupAccountGroupQueryServiceTest {

    @Mock
    private AccountGroupMapper accountGroupMapper;

    @Mock
    private AccountGroupMembershipMapper membershipMapper;

    @Mock
    private CountryService countryService;

    @InjectMocks
    private HistoricalGroupAccountGroupQueryService service;

    @Test
    void listsAccountGroupHistoryWithGroupLevelFactsAndCountry() {
        HistoricalGroupQuery query = new HistoricalGroupQuery();
        query.setAccountGroupId(12L);
        query.setPage(2);
        query.setPageSize(20);
        AccountGroup group = new AccountGroup();
        group.setId(12L);
        when(accountGroupMapper.selectById(12L)).thenReturn(group);
        when(membershipMapper.countHistoricalGroupsByAccountGroup(12L)).thenReturn(21L);

        HistoricalGroupPageRow row = new HistoricalGroupPageRow();
        row.setGroupJid("120363001@g.us");
        row.setSubject("账号组历史群");
        row.setInviteCode("InviteCode");
        row.setOwnerPhone("51943333070");
        row.setGroupCreatedAt(1720000000L);
        row.setKnownMembershipCount(2);
        row.setInGroupCount(2);
        row.setAdminInGroup(true);
        row.setOwnerInGroup(true);
        row.setAnnounceOnly(true);
        row.setMemberSize(88);
        row.setOperable(false);
        when(membershipMapper.selectHistoricalGroupPageByAccountGroup(12L, 20, 20))
                .thenReturn(List.of(row));
        when(membershipMapper.selectHistoricalGroupAccountPhonesByAccountGroup(
                12L, List.of("120363001@g.us")))
                .thenReturn(List.of(
                        accountPhone("120363001@g.us", "8613800000000", false),
                        accountPhone("120363001@g.us", "8613900000000", true),
                        accountPhone("120363001@g.us", "8613900000000", false)));
        when(countryService.resolveActiveCountriesByPhoneNumbers(List.of("51943333070")))
                .thenReturn(Map.of(
                        "51943333070",
                        new CountryReferenceVO(51L, "PE", "秘鲁", "+51", "🇵🇪")));

        PageResult<?> result = service.list(query);

        assertThat(result.total()).isEqualTo(21);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(20);
        assertThat(result.list()).singleElement().satisfies(raw -> {
            var item = (com.armada.group.model.vo.HistoricalGroupItemVO) raw;
            assertThat(item.accountPhones())
                    .containsExactly("8613900000000");
            assertThat(item.inviteLink())
                    .isEqualTo("https://chat.whatsapp.com/InviteCode");
            assertThat(item.countryIso2()).isEqualTo("PE");
            assertThat(item.countryName()).isEqualTo("秘鲁");
            assertThat(item.countryFlag()).isEqualTo("🇵🇪");
            assertThat(item.groupCreatedAt()).isEqualTo(1720000000L);
            assertThat(item.membershipState())
                    .isEqualTo(HistoricalGroupMembershipState.CURRENT_IN_GROUP);
            assertThat(item.selfRole()).isEqualTo(HistoricalGroupSelfRole.OWNER);
            assertThat(item.speechState()).isEqualTo(SpeechState.ADMIN_CAN_SPEAK);
            assertThat(item.operable()).isFalse();
            assertThat(item.disabledReason()).contains("在线");
        });
        verify(membershipMapper).selectHistoricalGroupPageByAccountGroup(12L, 20, 20);
        verify(membershipMapper).selectHistoricalGroupAccountPhonesByAccountGroup(
                12L, List.of("120363001@g.us"));
    }

    @Test
    void invalidOwnerIdentityLeavesCountryEmptyWithoutUsingAssociatedAccountCountry() {
        HistoricalGroupQuery query = new HistoricalGroupQuery();
        query.setAccountGroupId(12L);
        query.setPage(1);
        query.setPageSize(20);
        AccountGroup group = new AccountGroup();
        group.setId(12L);
        when(accountGroupMapper.selectById(12L)).thenReturn(group);
        when(membershipMapper.countHistoricalGroupsByAccountGroup(12L)).thenReturn(1L);

        HistoricalGroupPageRow row = new HistoricalGroupPageRow();
        row.setGroupJid("120363internal@g.us");
        row.setSubject("内部身份群");
        row.setOwnerPhone("193088878297313");
        row.setKnownMembershipCount(0);
        row.setInGroupCount(0);
        row.setOperable(false);
        when(membershipMapper.selectHistoricalGroupPageByAccountGroup(12L, 0, 20))
                .thenReturn(List.of(row));
        when(membershipMapper.selectHistoricalGroupAccountPhonesByAccountGroup(
                12L, List.of("120363internal@g.us")))
                .thenReturn(List.of(accountPhone(
                        "120363internal@g.us", "51943333070", false)));
        when(countryService.resolveActiveCountriesByPhoneNumbers(List.of("193088878297313")))
                .thenReturn(Map.of());

        PageResult<?> result = service.list(query);

        assertThat(result.list()).singleElement().satisfies(raw -> {
            var item = (com.armada.group.model.vo.HistoricalGroupItemVO) raw;
            assertThat(item.countryIso2()).isNull();
            assertThat(item.countryName()).isNull();
            assertThat(item.countryFlag()).isNull();
            assertThat(item.subject()).isEqualTo("内部身份群");
            assertThat(item.accountPhones()).containsExactly("51943333070");
        });
    }

    private static HistoricalGroupAccountPhoneRow accountPhone(
            String groupJid,
            String accountPhone,
            boolean currentRelation) {
        HistoricalGroupAccountPhoneRow row = new HistoricalGroupAccountPhoneRow();
        row.setGroupJid(groupJid);
        row.setAccountPhone(accountPhone);
        row.setCurrentRelation(currentRelation);
        return row;
    }
}
