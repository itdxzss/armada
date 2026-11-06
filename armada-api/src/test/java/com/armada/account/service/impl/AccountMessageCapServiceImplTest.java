package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.converter.AccountMessageCapConverter;
import com.armada.account.model.dto.AccountMessageCapQueryDTO;
import com.armada.account.model.enums.AccountMessageCapQueryStatus;
import com.armada.account.model.vo.AccountMessageCapBatchVO;
import com.armada.account.model.vo.AccountMessageCapItemVO;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolMessageCap;
import com.armada.platform.protocol.port.AccountMessageCapPort;
import com.armada.shared.exception.BusinessException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountMessageCapServiceImplTest {

    @Mock
    private AccountProtocolLookupService lookup;
    @Mock
    private AccountMessageCapPort port;

    private AccountMessageCapServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AccountMessageCapServiceImpl(lookup, port, Mappers.getMapper(AccountMessageCapConverter.class));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void isolatesEachAccountAndKeepsRequestOrder() {
        ProtocolAccountRef ok = ref(1L, ProtocolBackend.ANDROID);
        ProtocolAccountRef offline = ref(2L, ProtocolBackend.ANDROID);
        ProtocolAccountRef web = ref(3L, ProtocolBackend.WEB);
        ProtocolAccountRef rejected = ref(4L, ProtocolBackend.ANDROID);
        ProtocolAccountRef timeout = ref(6L, ProtocolBackend.ANDROID);
        when(lookup.findActiveProtocolRefs(List.of(1L, 2L, 3L, 4L, 5L, 6L)))
                .thenReturn(List.of(ok, offline, web, rejected, timeout));
        when(port.query(ok, true)).thenReturn(cap("NON_NUMERIC_QUOTA", "-1"));
        when(port.query(offline, true)).thenThrow(new ProtocolException(ProtocolErrorCode.ACCOUNT_NOT_ONLINE, "x"));
        when(port.query(web, true)).thenThrow(new ProtocolException(ProtocolErrorCode.UNSUPPORTED_BACKEND, "x"));
        when(port.query(rejected, true)).thenReturn(cap(ProtocolMessageCap.REJECTED, null));
        when(port.query(timeout, true)).thenThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "slow"));

        AccountMessageCapBatchVO result = service.query(new AccountMessageCapQueryDTO(List.of(1L, 2L, 3L, 4L, 5L, 6L), true));

        assertThat(result.requested()).isEqualTo(6);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(5);
        assertThat(result.items()).extracting(AccountMessageCapItemVO::accountId)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(result.items()).extracting(AccountMessageCapItemVO::queryStatus).containsExactly(
                AccountMessageCapQueryStatus.SUCCESS, AccountMessageCapQueryStatus.OFFLINE,
                AccountMessageCapQueryStatus.UNSUPPORTED_BACKEND, AccountMessageCapQueryStatus.REJECTED,
                AccountMessageCapQueryStatus.ACCOUNT_UNAVAILABLE, AccountMessageCapQueryStatus.FAILED);
        AccountMessageCapItemVO first = result.items().get(0);
        assertThat(first.cap().totalQuota()).isEqualTo("-1");
        assertThat(first.cap().remaining()).isNull();
        assertThat(first.phone()).isEqualTo("9190000000" + "01");
        assertThat(result.items().get(5).errorCode()).isEqualTo("TIMEOUT");
        assertThat(result.items().get(4).phone()).isNull();
        assertThat(result.items().get(1).cap()).isNull();
    }

    @Test
    void unexpectedRuntimeErrorOnlyFailsThatAccountWithoutLeakingMessage() {
        ProtocolAccountRef first = ref(1L, ProtocolBackend.ANDROID);
        ProtocolAccountRef second = ref(2L, ProtocolBackend.ANDROID);
        when(lookup.findActiveProtocolRefs(List.of(1L, 2L))).thenReturn(List.of(first, second));
        when(port.query(first, false)).thenThrow(new IllegalStateException("secret-detail"));
        when(port.query(second, false)).thenReturn(cap("OK", "10"));

        AccountMessageCapBatchVO result = service.query(new AccountMessageCapQueryDTO(List.of(1L, 2L), false));

        assertThat(result.items().get(0).queryStatus()).isEqualTo(AccountMessageCapQueryStatus.FAILED);
        assertThat(result.items().get(0).errorCode()).isEqualTo("IllegalStateException");
        assertThat(result.items().get(1).queryStatus()).isEqualTo(AccountMessageCapQueryStatus.SUCCESS);
    }

    @Test
    void deduplicatesIdsAndQueriesEachAccountOnce() {
        ProtocolAccountRef ok = ref(1L, ProtocolBackend.ANDROID);
        when(lookup.findActiveProtocolRefs(List.of(1L))).thenReturn(List.of(ok));
        when(port.query(any(), anyBoolean())).thenReturn(cap("OK", "10"));

        AccountMessageCapBatchVO result = service.query(new AccountMessageCapQueryDTO(Arrays.asList(1L, null, 1L), false));

        assertThat(result.requested()).isEqualTo(1);
        verify(port, times(1)).query(ok, false);
    }

    @Test
    void rejectsEmptyAndOversizedSelections() {
        assertThatThrownBy(() -> service.query(new AccountMessageCapQueryDTO(List.of(), false)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.query(null)).isInstanceOf(BusinessException.class);
        List<Long> tooMany = new ArrayList<>();
        for (long id = 1; id <= AccountMessageCapServiceImpl.MAX_ACCOUNTS + 1; id++) {
            tooMany.add(id);
        }
        assertThatThrownBy(() -> service.query(new AccountMessageCapQueryDTO(tooMany, false)))
                .isInstanceOf(BusinessException.class);
    }

    private static ProtocolAccountRef ref(Long id, ProtocolBackend backend) {
        return new ProtocolAccountRef(id, backend, "acc_" + id, "9190000000" + String.format("%02d", id));
    }

    private static ProtocolMessageCap cap(String status, String total) {
        return new ProtocolMessageCap(status, total, "0", null, "NONE", "1", "2", "3", "NOT_ELIGIBLE",
                "NOT_ELIGIBLE", null, null, null, "2026-11-05T12:53:58Z", null, null);
    }
}
