package com.armada.marketing.service;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.marketing.mapper.GroupCreationMarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.dto.CreateGroupCreationMarketingTaskDTO;
import com.armada.marketing.model.dto.GroupCreationMarketingMaterialDTO;
import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.armada.marketing.model.entity.GroupCreationMarketingTask;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.enums.GroupCreationMarketingTaskStatus;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.armada.marketing.service.impl.GroupCreationMarketingTaskServiceImpl;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupCreationMarketingTaskServiceImplUnitTest {

    @Mock
    private GroupCreationMarketingTaskMapper mapper;
    @Mock
    private MarketingTemplateMapper templateMapper;
    @Mock
    private MarketingTaskMapper marketingTaskMapper;

    private GroupCreationMarketingTaskService service;

    @BeforeEach
    void setUp() {
        service = new GroupCreationMarketingTaskServiceImpl(mapper, templateMapper, marketingTaskMapper);
    }

    @Test
    void stopDoesNotStopMarketingTaskWhenParentTaskIsAlreadyFinal() {
        GroupCreationMarketingTask task = new GroupCreationMarketingTask();
        task.setId(7L);
        task.setStatus(GroupCreationMarketingTaskStatus.SUCCESS.code());
        task.setMarketingTaskId(19L);
        when(mapper.selectTaskById(7L)).thenReturn(task);
        when(mapper.countStoppableItems(7L)).thenReturn(0);
        when(mapper.stopTask(eq(7L), eq(GroupCreationMarketingTaskStatus.STOPPED.code()), eq(0), anyLong()))
                .thenReturn(0);

        int updated = service.stopTask(7L);

        assertThat(updated).isZero();
        verify(marketingTaskMapper, never()).stopTask(anyLong(), anyLong());
    }

    @Test
    void createTaskMatchesOnlyNormalOnlineUsableAccounts() {
        MarketingTemplate template = new MarketingTemplate();
        template.setId(18L);
        template.setTemplateName("活动模板");
        when(templateMapper.selectById(18L)).thenReturn(template);
        when(mapper.selectAccountCandidatesByGroupId(8L)).thenReturn(List.of(
                candidate(1L, "", AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE, 1, null),
                candidate(2L, "acc_offline", AccountStateCode.NORMAL, AccountLoginStateCode.OFFLINE, 1, null),
                candidate(3L, "acc_banned", AccountStateCode.BANNED, AccountLoginStateCode.ONLINE, 1, null),
                candidate(4L, "acc_risk", AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE, 2, null),
                candidate(5L, "acc_muted", AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE, 1, 1),
                candidate(6L, "acc_usable", AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE, 1, null)
        ));
        when(mapper.insertTask(any(GroupCreationMarketingTask.class))).thenAnswer(invocation -> {
            GroupCreationMarketingTask task = invocation.getArgument(0);
            task.setId(99L);
            return 1;
        });
        when(mapper.insertItems(anyList())).thenReturn(1);
        GroupCreationMarketingTask storedTask = new GroupCreationMarketingTask();
        storedTask.setId(99L);
        storedTask.setTaskName("建群营销");
        storedTask.setAccountGroupId(8L);
        storedTask.setAccountGroupName("A组");
        storedTask.setMarketingTemplateId(18L);
        storedTask.setMarketingTemplateName("活动模板");
        storedTask.setStatus(GroupCreationMarketingTaskStatus.PENDING.code());
        storedTask.setMatchedItemCount(1);
        storedTask.setUnmatchedFileCount(1);
        storedTask.setSuccessCount(0);
        storedTask.setFailedCount(0);
        storedTask.setAbandonedCount(0);
        storedTask.setSendIntervalSeconds(30);
        when(mapper.selectTaskById(99L)).thenReturn(storedTask);
        when(mapper.selectItemsByTaskId(99L)).thenReturn(List.of());

        service.createTask(new CreateGroupCreationMarketingTaskDTO(
                "建群营销",
                8L,
                "A组",
                18L,
                "活动模板",
                30,
                "活动群",
                null,
                List.of(
                        new GroupCreationMarketingMaterialDTO("a.txt", "8613900000000"),
                        new GroupCreationMarketingMaterialDTO("b.txt", "8613911111111"))));

        ArgumentCaptor<GroupCreationMarketingTask> taskCaptor =
                ArgumentCaptor.forClass(GroupCreationMarketingTask.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupCreationMarketingItem>> itemCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mapper).insertTask(taskCaptor.capture());
        verify(mapper).insertItems(itemCaptor.capture());
        assertThat(taskCaptor.getValue().getMatchedItemCount()).isEqualTo(1);
        assertThat(taskCaptor.getValue().getUnmatchedFileCount()).isEqualTo(1);
        assertThat(itemCaptor.getValue()).singleElement().satisfies(item -> {
            assertThat(item.getAccountId()).isEqualTo(6L);
            assertThat(item.getProtocolAccountId()).isEqualTo("acc_usable");
            assertThat(item.getFileName()).isEqualTo("a.txt");
        });
    }

    private static GroupCreationMarketingAccountCandidate candidate(Long accountId,
                                                                    String protocolAccountId,
                                                                    Integer accountState,
                                                                    Integer loginState,
                                                                    Integer riskStatus,
                                                                    Integer muteStatus) {
        GroupCreationMarketingAccountCandidate candidate = new GroupCreationMarketingAccountCandidate();
        candidate.setAccountId(accountId);
        candidate.setAccountPhone("86130000000" + accountId);
        candidate.setProtocolAccountId(protocolAccountId);
        candidate.setAccountState(accountState);
        candidate.setLoginState(loginState);
        candidate.setRiskStatus(riskStatus);
        candidate.setMuteStatus(muteStatus);
        return candidate;
    }
}
