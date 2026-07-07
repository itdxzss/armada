package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
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
import com.armada.platform.protocol.model.result.GroupCreateParticipantResult;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.shared.exception.BusinessException;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        service = new GroupOperationServiceImpl(accountMapper, groupCreatePort);
    }

    @Test
    void createGroupResolvesOnlineProtocolAccountAndMapsResult() {
        Account account = new Account();
        account.setId(7L);
        account.setProtocolAccountId("acc_861111");
        when(accountMapper.selectActiveById(7L)).thenReturn(account);
        when(accountMapper.selectOnlineAccountIdsByIds(List.of(7L), AccountLoginStateCode.ONLINE))
                .thenReturn(List.of(7L));
        when(groupCreatePort.create("acc_861111", "测试群",
                List.of("+86 139-0000-0000", "8613911111111@s.whatsapp.net")))
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
        verify(groupCreatePort).create("acc_861111", "测试群",
                List.of("+86 139-0000-0000", "8613911111111@s.whatsapp.net"));
    }

    @Test
    void createGroupRejectsOfflineAccountBeforeCallingProtocol() {
        Account account = new Account();
        account.setId(7L);
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

        verify(groupCreatePort, never()).create(eq("acc_861111"), eq("测试群"), eq(List.of("8613900000000")));
    }

    @Test
    void createGroupAllowsMoreThanFiftyParticipants() {
        Account account = new Account();
        account.setId(7L);
        account.setProtocolAccountId("acc_861111");
        List<String> participants = IntStream.rangeClosed(1, 51)
                .mapToObj(i -> "861390000%04d".formatted(i))
                .toList();
        when(accountMapper.selectActiveById(7L)).thenReturn(account);
        when(accountMapper.selectOnlineAccountIdsByIds(List.of(7L), AccountLoginStateCode.ONLINE))
                .thenReturn(List.of(7L));
        when(groupCreatePort.create("acc_861111", "测试群", participants))
                .thenReturn(new GroupCreateResult("120363create@g.us", false, List.of()));

        GroupCreateVO result = service.createGroup(new GroupCreateDTO(7L, "测试群", participants));

        assertThat(result.groupJid()).isEqualTo("120363create@g.us");
        verify(groupCreatePort).create("acc_861111", "测试群", participants);
    }

    @Test
    void createGroupMapsProtocolAccountBusyToBusinessConflict() {
        Account account = new Account();
        account.setId(7L);
        account.setProtocolAccountId("acc_861111");
        when(accountMapper.selectActiveById(7L)).thenReturn(account);
        when(accountMapper.selectOnlineAccountIdsByIds(List.of(7L), AccountLoginStateCode.ONLINE))
                .thenReturn(List.of(7L));
        when(groupCreatePort.create("acc_861111", "测试群", List.of("8613900000000")))
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
}
