package com.armada.marketing.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.armada.account.service.AccountService;
import com.armada.marketing.converter.ScriptMarketingConverter;
import com.armada.marketing.mapper.ScriptMarketingTaskMapper;
import com.armada.marketing.mapper.ScriptMarketingGroupMapper;
import com.armada.marketing.mapper.ScriptMarketingSendRecordMapper;
import com.armada.marketing.model.dto.ScriptMarketingStepDTO;
import com.armada.marketing.model.entity.ScriptMarketingTask;
import com.armada.marketing.model.entity.ScriptMarketingGroup;
import com.armada.marketing.model.entity.ScriptMarketingSendRecord;
import com.armada.marketing.script.service.ScriptMarketingTaskService;
import com.armada.marketing.script.service.ScriptMarketingContentService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.paging.PageQuery;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** 任务详情只补充当前配置、绑定及当前页记录所需的手机号。 */
class ScriptMarketingTaskPhoneTest {
    private final ScriptMarketingTaskMapper tasks = mock(ScriptMarketingTaskMapper.class);
    private final ScriptMarketingGroupMapper groups = mock(ScriptMarketingGroupMapper.class);
    private final ScriptMarketingSendRecordMapper records = mock(ScriptMarketingSendRecordMapper.class);
    private final ScriptMarketingContentService content = mock(ScriptMarketingContentService.class);
    private final AccountService accounts = mock(AccountService.class);
    private final ScriptMarketingTaskService service = new ScriptMarketingTaskService(tasks, groups, records,
            Mappers.getMapper(ScriptMarketingConverter.class), content, null, null, null, null, accounts);

    @BeforeEach
    void ownedTask() {
        var task = new ScriptMarketingTask(); task.setId(12L); task.setCreatedBy(1L); task.setStepsJson("steps");
        when(tasks.find(12L)).thenReturn(task);
    }
    @Test
    void detailCombinesConfiguredAndBoundAccountsInOneLookup() {
        when(content.decode("steps")).thenReturn(List.of(
                new ScriptMarketingStepDTO("ADMIN",685L,null,"admin",null,null, null, null),
                new ScriptMarketingStepDTO("PROMOTER",null,null,"p1",null,null, null, null)));
        var group = new ScriptMarketingGroup(); group.setBindingsJson("bindings");
        when(groups.list(12L)).thenReturn(List.of(group));
        when(content.decodeBindings("bindings")).thenReturn(Map.of("admin",685L,"p1",690L));
        when(accounts.getPhonesByIds(List.of(685L,690L))).thenReturn(Map.of(685L,"15550000685",690L,"15550000690"));
        assertThat(service.detail(12L,1L).accountPhones())
                .isEqualTo(Map.of(685L,"15550000685",690L,"15550000690"));
        verify(accounts, times(1)).getPhonesByIds(List.of(685L,690L));
    }
    @Test
    void recordPageIncludesPhoneAndPreservesMissingPhoneWithoutIdFallback() {
        var query = new PageQuery();
        var first = new ScriptMarketingSendRecord(); first.setAccountId(685L); first.setId(10L);
        var repeated = new ScriptMarketingSendRecord(); repeated.setAccountId(685L);
        var missing = new ScriptMarketingSendRecord(); missing.setAccountId(999L);
        when(records.page(12L,query)).thenReturn(List.of(first,repeated,missing));
        when(records.count(12L)).thenReturn(25L);
        when(accounts.getPhonesByIds(List.of(685L,999L))).thenReturn(Map.of(685L,"15550000685"));
        var result = service.records(12L,query,1L);
        assertThat(result.total()).isEqualTo(25);
        assertThat(result.list()).extracting(row -> row.accountPhone())
                .containsExactly("15550000685","15550000685",null);
        assertThat(result.list().get(0).id()).isEqualTo(10L);
        verify(accounts, times(1)).getPhonesByIds(List.of(685L,999L));
    }
    @Test
    void anotherOwnerCannotReadPhones() {
        assertThatThrownBy(() -> service.detail(12L,2L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.records(12L,new PageQuery(),2L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(accounts,groups,records);
    }
}
