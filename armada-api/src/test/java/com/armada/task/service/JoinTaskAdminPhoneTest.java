package com.armada.task.service;

import com.armada.account.service.AccountService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.model.vo.JoinResultRowVO;
import com.armada.task.service.impl.JoinTaskServiceImpl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 操作管理员展示使用对应账号手机号，缺失时不以 ID 冒充号码。 */
class JoinTaskAdminPhoneTest {
    private final JoinTaskResultMapper results = mock(JoinTaskResultMapper.class);
    private final AccountService accounts = mock(AccountService.class);
    private final JoinTaskServiceImpl service = new JoinTaskServiceImpl(mock(JoinTaskMapper.class), results,
            mock(GroupLinkRegistryService.class), accounts);

    @Test
    void resolvesDistinctActorsAndPreservesOrderAndMissingPhones() {
        when(results.selectResultsByTask(12L)).thenReturn(List.of(row(2548L), row(2547L), row(2548L), row(null), row(999L)));
        when(accounts.getPhonesByIds(List.of(2548L, 2547L, 999L)))
                .thenReturn(Map.of(2548L, "15550002548", 2547L, "15550002547"));
        var rows = service.results(12L);
        assertThat(rows).extracting(JoinResultRowVO::adminActorPhone)
                .containsExactly("15550002548", "15550002547", "15550002548", null, null);
        assertThat(rows).extracting(JoinResultRowVO::adminActorAccountId)
                .containsExactly(2548L, 2547L, 2548L, null, 999L);
        assertThat(rows).extracting(JoinResultRowVO::account).containsOnly("15550000001");
        verify(accounts).getPhonesByIds(List.of(2548L, 2547L, 999L));
    }

    @Test
    void noActorOrNoResultsDoesNotQueryAccounts() {
        when(results.selectResultsByTask(12L)).thenReturn(List.of(row(null)), List.of());
        assertThat(service.results(12L)).extracting(JoinResultRowVO::adminActorPhone).containsExactly((String) null);
        assertThat(service.results(12L)).isEmpty();
        verifyNoInteractions(accounts);
    }

    private static JoinTaskResult row(Long actorId) {
        var row = new JoinTaskResult();
        row.setAccount("15550000001");
        row.setStatus("SUCCESS");
        row.setAdminActorAccountId(actorId);
        return row;
    }
}
