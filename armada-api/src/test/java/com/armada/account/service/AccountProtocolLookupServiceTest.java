package com.armada.account.service;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.service.impl.AccountProtocolLookupServiceImpl;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountProtocolLookupServiceTest {

    private static final DataScope USER_SCOPE = DataScope.self(11L);

    @Mock
    private AccountMapper accountMapper;

    private AccountProtocolLookupService service;

    @BeforeEach
    void setUp() {
        DataScopeContext.open(USER_SCOPE);
        service = new AccountProtocolLookupServiceImpl(accountMapper);
    }

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
    }

    @Test
    void phoneLookupPassesTheCurrentUserScopeToTheMapper() {
        Account account = account(1L, "WEB", "web-1", "911");
        when(accountMapper.selectActiveByWsPhonesForScope(List.of("911"), USER_SCOPE))
                .thenReturn(List.of(account));

        assertThat(service.findActiveProtocolRefsByPhones(List.of("911")))
                .containsExactly(Map.entry(
                        "911", new ProtocolAccountRef(1L, ProtocolBackend.WEB, "web-1", "911")));
        verify(accountMapper).selectActiveByWsPhonesForScope(List.of("911"), USER_SCOPE);
    }

    @Test
    void findActiveProtocolRefs_preservesRequestOrderAndMapsBackend() {
        when(accountMapper.selectActiveByIdsForScope(List.of(3L, 1L, 2L), USER_SCOPE))
                .thenReturn(List.of(
                        account(1L, "android", "android-1", "911"),
                        account(3L, "WEB", "web-3", "933")));

        assertThat(service.findActiveProtocolRefs(List.of(3L, 1L, 2L)))
                .containsExactly(
                        new ProtocolAccountRef(3L, ProtocolBackend.WEB, "web-3", "933"),
                        new ProtocolAccountRef(1L, ProtocolBackend.ANDROID, "android-1", "911"));
    }

    @Test
    void findActiveProtocolRefs_skipsRowsWithIncompleteProtocolIdentity() {
        when(accountMapper.selectActiveByIdsForScope(
                List.of(1L, 2L, 3L, 4L), USER_SCOPE))
                .thenReturn(List.of(
                        account(1L, " ", "acc-1", "911"),
                        account(2L, "WEB", " ", "922"),
                        account(3L, "ANDROID", "acc-3", null),
                        account(4L, "WEB", "acc-4", "944")));

        assertThat(service.findActiveProtocolRefs(List.of(1L, 2L, 3L, 4L)))
                .containsExactly(
                        new ProtocolAccountRef(1L, ProtocolBackend.WEB, "acc-1", "911"),
                        new ProtocolAccountRef(4L, ProtocolBackend.WEB, "acc-4", "944"));
    }

    @Test
    void findActiveProtocolRef_legacyWebWithoutProtocolIdUsesWebFallback() {
        when(accountMapper.selectActiveByIdForScope(302L, USER_SCOPE))
                .thenReturn(account(302L, null, "acc_919755599869", "919755599869"));

        assertThat(service.findActiveProtocolRef(302L))
                .contains(new ProtocolAccountRef(
                        302L,
                        ProtocolBackend.WEB,
                        "acc_919755599869",
                        "919755599869"));
    }

    @Test
    void findActiveProtocolRefs_emptyInputDoesNotQueryMapper() {
        assertThat(service.findActiveProtocolRefs(List.of())).isEmpty();
        assertThat(service.findActiveProtocolRefs(null)).isEmpty();
        verifyNoInteractions(accountMapper);
    }

    @Test
    void findOnlineProtocolRefs_filtersOfflineAccountsAndPreservesRequestOrder() {
        when(accountMapper.selectOnlineAccountIdsByIdsForScope(
                List.of(3L, 1L, 2L), AccountLoginStateCode.ONLINE, USER_SCOPE))
                .thenReturn(List.of(1L, 3L));
        when(accountMapper.selectActiveByIdsForScope(List.of(3L, 1L), USER_SCOPE))
                .thenReturn(List.of(
                        account(1L, "ANDROID", "android-1", "911"),
                        account(3L, "WEB", "web-3", "933")));

        assertThat(service.findOnlineProtocolRefs(List.of(3L, 1L, 2L)))
                .containsExactly(
                        new ProtocolAccountRef(3L, ProtocolBackend.WEB, "web-3", "933"),
                        new ProtocolAccountRef(1L, ProtocolBackend.ANDROID, "android-1", "911"));
    }

    @Test
    void findRandomOnlineNormalWebByGroupIdUsesDedicatedWebSelector() {
        when(accountMapper.selectRandomOnlineNormalWebByGroupIdForScope(
                301L, AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE,
                1, "WEB", USER_SCOPE))
                .thenReturn(account(51L, "WEB", "web-51", "9551"));

        assertThat(service.findRandomOnlineNormalWebByGroupId(301L))
                .contains(new ProtocolAccountRef(51L, ProtocolBackend.WEB, "web-51", "9551"));
        verify(accountMapper).selectRandomOnlineNormalWebByGroupIdForScope(
                301L, AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE,
                1, "WEB", USER_SCOPE);
    }

    @Test
    void findOnlineNormalByGroupIdReturnsEveryEligibleProtocolAccount() {
        when(accountMapper.selectOnlineNormalByGroupIdForScope(
                301L, AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE, USER_SCOPE))
                .thenReturn(List.of(
                        account(51L, "WEB", "web-51", "9551"),
                        account(52L, "ANDROID", "android-52", "9552")));

        assertThat(service.findOnlineNormalByGroupId(301L)).containsExactly(
                new ProtocolAccountRef(51L, ProtocolBackend.WEB, "web-51", "9551"),
                new ProtocolAccountRef(52L, ProtocolBackend.ANDROID, "android-52", "9552"));
        verify(accountMapper).selectOnlineNormalByGroupIdForScope(
                301L, AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE, USER_SCOPE);
    }

    @Test
    void findOnlineNormalStrictByGroupIdSkipsUnsupportedProtocolRows() {
        when(accountMapper.selectOnlineNormalByGroupIdForScope(
                301L, AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE, USER_SCOPE))
                .thenReturn(List.of(
                        account(51L, "WEB", "web-51", "9551"),
                        account(52L, "DESKTOP", "desktop-52", "9552"),
                        account(53L, "ANDROID", "android-53", "9553")));

        assertThat(service.findOnlineNormalStrictByGroupId(301L)).containsExactly(
                new ProtocolAccountRef(51L, ProtocolBackend.WEB, "web-51", "9551"),
                new ProtocolAccountRef(53L, ProtocolBackend.ANDROID, "android-53", "9553"));
    }

    private static Account account(Long id, String protocolId, String protocolAccountId, String wsPhone) {
        Account account = new Account();
        account.setId(id);
        account.setProtocolId(protocolId);
        account.setProtocolAccountId(protocolAccountId);
        account.setWsPhone(wsPhone);
        return account;
    }
}
