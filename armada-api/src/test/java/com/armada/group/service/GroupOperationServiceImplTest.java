package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.group.model.dto.GroupCreateDTO;
import com.armada.group.model.vo.GroupCreateVO;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.group.service.impl.GroupOperationServiceImpl;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupCreateParticipantResult;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupOperationServiceImplTest {

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private GroupCreatePort groupCreatePort;

    private GroupOperationServiceImpl service;

    @BeforeEach
    void setUp() {
        DataScopeContext.open(DataScope.all(1L));
        service = new GroupOperationServiceImpl(accountMapper, groupCreatePort);
    }

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
    }

    @Test
    void createGroupResolvesOnlineProtocolAccountAndMapsResult() {
        Account account = new Account();
        account.setId(7L);
        account.setOwnerUserId(1L);
        account.setProtocolId("WEB");
        account.setProtocolAccountId("acc_861111");
        account.setWsPhone("861111");
        when(accountMapper.selectActiveById(7L)).thenReturn(account);
        when(accountMapper.selectOnlineAccountIdsByIds(List.of(7L), AccountLoginStateCode.ONLINE))
                .thenReturn(List.of(7L));
        when(groupCreatePort.create(any(GroupCreateCommand.class)))
                .thenReturn(new GroupCreateResult(
                        "120363create@g.us",
                        false,
                        List.of(
                                new GroupCreateParticipantResult(
                                        "8613900000000@s.whatsapp.net", "OK", "200"),
                                new GroupCreateParticipantResult(
                                        "8613911111111@s.whatsapp.net", "PRIVACY_BLOCKED", "403"))));

        GroupCreateVO result = service.createGroup(new GroupCreateDTO(
                7L,
                " 测试群 ",
                List.of("+86 139-0000-0000", "8613911111111@s.whatsapp.net")));

        assertThat(result.groupJid()).isEqualTo("120363create@g.us");
        assertThat(result.partial()).isFalse();
        assertThat(result.results()).hasSize(2);
        assertThat(result.results().get(0).status()).isEqualTo("OK");
        ArgumentCaptor<GroupCreateCommand> command = ArgumentCaptor.forClass(GroupCreateCommand.class);
        verify(groupCreatePort).create(command.capture());
        assertThat(command.getValue().account().backend()).isEqualTo(ProtocolBackend.WEB);
        assertThat(command.getValue().account().protocolAccountId()).isEqualTo("acc_861111");
        assertThat(command.getValue().account().wsPhone()).isEqualTo("861111");
        assertThat(command.getValue().subject()).isEqualTo("测试群");
        assertThat(command.getValue().participants())
                .containsExactly("+86 139-0000-0000", "8613911111111@s.whatsapp.net");
        assertThat(command.getValue().announceOnly()).isFalse();
        assertThat(command.getValue().operationId()).startsWith("group-create-api:");
    }

    @Test
    void createGroupRejectsOfflineAccountBeforeCallingProtocol() {
        Account account = new Account();
        account.setId(7L);
        account.setOwnerUserId(1L);
        account.setProtocolAccountId("acc_861111");
        when(accountMapper.selectActiveById(7L)).thenReturn(account);
        when(accountMapper.selectOnlineAccountIdsByIds(List.of(7L), AccountLoginStateCode.ONLINE))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.createGroup(new GroupCreateDTO(
                7L,
                "测试群",
                List.of("8613900000000"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("操作账号未在线");

        verify(groupCreatePort, never()).create(any(GroupCreateCommand.class));
    }

    @Test
    void createGroupHidesAnotherUsersAccountBeforeCheckingOnlineState() {
        Account account = new Account();
        account.setId(7L);
        account.setOwnerUserId(2L);
        account.setProtocolAccountId("acc_861111");
        when(accountMapper.selectActiveById(7L)).thenReturn(account);

        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.self(1L))) {
            assertThatThrownBy(() -> service.createGroup(new GroupCreateDTO(
                    7L,
                    "测试群",
                    List.of("8613900000000"))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("账号不存在");
        }

        verify(accountMapper, never()).selectOnlineAccountIdsByIds(any(), anyInt());
        verify(groupCreatePort, never()).create(any(GroupCreateCommand.class));
    }

    @Test
    void createGroupRejectsHistoricalUnownedAccountForAdministrator() {
        Account account = new Account();
        account.setId(7L);
        account.setProtocolAccountId("acc_861111");
        when(accountMapper.selectActiveById(7L)).thenReturn(account);

        assertThatThrownBy(() -> service.createGroup(new GroupCreateDTO(
                7L,
                "测试群",
                List.of("8613900000000"))))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(
                                com.armada.shared.exception.ErrorCode.ACCESS_DENIED.code()));

        verify(accountMapper, never()).selectOnlineAccountIdsByIds(any(), anyInt());
        verify(groupCreatePort, never()).create(any(GroupCreateCommand.class));
    }

    @Test
    void createGroupAllowsMoreThanFiftyParticipants() {
        Account account = new Account();
        account.setId(7L);
        account.setOwnerUserId(1L);
        account.setProtocolId("WEB");
        account.setProtocolAccountId("acc_861111");
        account.setWsPhone("861111");
        List<String> participants = IntStream.rangeClosed(1, 51)
                .mapToObj(i -> "861390000%04d".formatted(i))
                .toList();
        when(accountMapper.selectActiveById(7L)).thenReturn(account);
        when(accountMapper.selectOnlineAccountIdsByIds(List.of(7L), AccountLoginStateCode.ONLINE))
                .thenReturn(List.of(7L));
        when(groupCreatePort.create(any(GroupCreateCommand.class)))
                .thenReturn(new GroupCreateResult("120363create@g.us", false, List.of()));

        GroupCreateVO result = service.createGroup(new GroupCreateDTO(7L, "测试群", participants));

        assertThat(result.groupJid()).isEqualTo("120363create@g.us");
        ArgumentCaptor<GroupCreateCommand> command = ArgumentCaptor.forClass(GroupCreateCommand.class);
        verify(groupCreatePort).create(command.capture());
        assertThat(command.getValue().participants()).containsExactlyElementsOf(participants);
    }

    @Test
    void createGroupMapsProtocolAccountBusyToBusinessConflict() {
        Account account = new Account();
        account.setId(7L);
        account.setOwnerUserId(1L);
        account.setProtocolId("WEB");
        account.setProtocolAccountId("acc_861111");
        account.setWsPhone("861111");
        when(accountMapper.selectActiveById(7L)).thenReturn(account);
        when(accountMapper.selectOnlineAccountIdsByIds(List.of(7L), AccountLoginStateCode.ONLINE))
                .thenReturn(List.of(7L));
        when(groupCreatePort.create(any(GroupCreateCommand.class)))
                .thenThrow(new ProtocolException(
                        ProtocolErrorCode.ACCOUNT_BUSY,
                        ProtocolException.Metadata.of(429, "ACCOUNT_BUSY", 3000L, null),
                        "协议层错误 429 ACCOUNT_BUSY: account group operation in progress",
                        null));

        assertThatThrownBy(() -> service.createGroup(new GroupCreateDTO(
                7L,
                "测试群",
                List.of("8613900000000"))))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(40901);
                    assertThat(ex.getMessage()).contains("账号群操作繁忙");
                    assertThat(ex.getMessage()).contains("3000");
                });
    }

    @Test
    void createGroupRoutesAndroidAccountUsingCurrentProtocolFacts() {
        Account account = new Account();
        account.setId(7L);
        account.setOwnerUserId(1L);
        account.setProtocolId("ANDROID");
        account.setProtocolAccountId("acc_android");
        account.setWsPhone("919000000001");
        when(accountMapper.selectActiveById(7L)).thenReturn(account);
        when(accountMapper.selectOnlineAccountIdsByIds(List.of(7L), AccountLoginStateCode.ONLINE))
                .thenReturn(List.of(7L));
        when(groupCreatePort.create(any(GroupCreateCommand.class)))
                .thenReturn(new GroupCreateResult("120363create@g.us", false, List.of()));

        service.createGroup(new GroupCreateDTO(7L, "测试群", List.of("919000000002")));

        ArgumentCaptor<GroupCreateCommand> command = ArgumentCaptor.forClass(GroupCreateCommand.class);
        verify(groupCreatePort).create(command.capture());
        assertThat(command.getValue().account().backend()).isEqualTo(ProtocolBackend.ANDROID);
        assertThat(command.getValue().account().protocolAccountId()).isEqualTo("acc_android");
        assertThat(command.getValue().account().wsPhone()).isEqualTo("919000000001");
        assertThat(command.getValue().subject()).isEqualTo("测试群");
        assertThat(command.getValue().participants()).containsExactly("919000000002");
        assertThat(command.getValue().announceOnly()).isFalse();
    }
}
