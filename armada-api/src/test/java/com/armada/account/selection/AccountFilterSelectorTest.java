package com.armada.account.selection;

import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
import com.armada.account.selection.model.SelectedAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 账号圈选服务的纯 Mockito 测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountFilterSelectorTest {

    @Mock
    private AccountFilterSelectionMapper mapper;

    private AccountFilterSelector selector() {
        return new AccountFilterSelector(mapper, new ObjectMapper());
    }

    @Test
    void passesParsedCriteriaToMapper() {
        when(mapper.selectAccounts(any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());

        selector().select("{\"groupIds\":[7]}", 100);

        ArgumentCaptor<AccountFilterCriteria> captor =
                ArgumentCaptor.forClass(AccountFilterCriteria.class);
        verify(mapper).selectAccounts(captor.capture(), anyInt(), anyInt(), anyInt());
        assertThat(captor.getValue().groupIds()).containsExactly(7L);
    }

    @Test
    void alwaysInjectsNormalAndNotExportedAccountState() {
        // 设计 §2.7 强制注入：account_status=normal、is_exported=false
        when(mapper.selectAccounts(any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());

        selector().select("{}", 100);

        verify(mapper).selectAccounts(
                any(),
                eq(AccountFilterSelector.ACCOUNT_STATE_NORMAL),
                eq(AccountFilterSelector.ACCOUNT_STATE_EXPORTED),
                eq(100));
    }

    @Test
    void returnsEmptyListWhenLimitIsNotPositive() {
        // 上限非正数时不该退化成全表扫描
        assertThat(selector().select("{}", 0)).isEmpty();
        assertThat(selector().select("{}", -1)).isEmpty();
    }

    @Test
    void returnsMapperRowsUnchanged() {
        SelectedAccount row = new SelectedAccount(11L, "8613800000000", "web", "acc_8613800000000");
        when(mapper.selectAccounts(any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of(row));

        List<SelectedAccount> selected = selector().select("{}", 50);

        assertThat(selected).containsExactly(row);
    }

    @Test
    void tolerantOfNullMapperResult() {
        when(mapper.selectAccounts(any(), anyInt(), anyInt(), anyInt())).thenReturn(null);

        assertThat(selector().select("{}", 50)).isEmpty();
    }
    @Test
    void countUsesTheSameForcedInjectionAsSelection() {
        when(mapper.countAccounts(any(), eq(2), eq(4))).thenReturn(5000);

        assertThat(selector().count("{\"accountType\":1}")).isEqualTo(5000);
    }

    @Test
    void countIsNotTruncatedByTheSelectionLimit() {
        // 用 select(...).size() 试算会把「命中 5000」显示成「命中 10」
        when(mapper.countAccounts(any(), anyInt(), anyInt())).thenReturn(5000);

        assertThat(selector().count("{}")).isEqualTo(5000);
        verify(mapper, org.mockito.Mockito.never()).selectAccounts(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void countTreatsAnIllegalFilterAsUnrestricted() {
        when(mapper.countAccounts(any(), anyInt(), anyInt())).thenReturn(9);

        assertThat(selector().count("not json")).isEqualTo(9);
        assertThat(selector().count(null)).isEqualTo(9);
    }

}
