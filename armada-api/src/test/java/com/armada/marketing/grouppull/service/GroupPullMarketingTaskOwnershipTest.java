package com.armada.marketing.grouppull.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.dto.CreateGroupPullMarketingTaskDTO;
import com.armada.marketing.grouppull.service.impl.GroupPullMarketingTaskServiceImpl;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.marketing.service.impl.MarketingGroupOccupancyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 拉群营销任务跨域引用账号分组时的归属边界测试。 */
@ExtendWith(MockitoExtension.class)
class GroupPullMarketingTaskOwnershipTest {

    @Mock
    private GroupPullMarketingMapper mapper;
    @Mock
    private MarketingTaskMapper marketingTaskMapper;
    @Mock
    private MarketingTemplateMapper templateMapper;
    @Mock
    private AccountGroupMapper accountGroupMapper;
    @Mock
    private AccountProtocolLookupService accountProtocolLookupService;
    @Mock
    private GroupPullMarketingMaterialParser materialParser;
    @Mock
    private MarketingGroupOccupancyService groupOccupancyService;
    @Mock
    private MarketingAccountOccupancyService accountOccupancyService;

    private GroupPullMarketingTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GroupPullMarketingTaskServiceImpl(
                mapper,
                marketingTaskMapper,
                templateMapper,
                accountGroupMapper,
                accountProtocolLookupService,
                materialParser,
                groupOccupancyService,
                accountOccupancyService,
                new GroupPullMaterialEntryDelayPolicy());
    }

    @Test
    void createRejectsForeignBuilderGroupBeforeReadingAccounts() {
        when(materialParser.parse(any())).thenReturn(List.of(material(1)));
        when(accountGroupMapper.selectById(1L)).thenReturn(group(1L, 22L));

        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.self(11L))) {
            assertThatThrownBy(() -> service.create(request(1L, 2L), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("分组不存在");
        }

        verify(accountProtocolLookupService, never()).findRandomOnlineNormalByGroupId(anyLong());
        verify(marketingTaskMapper, never()).insertTask(any());
    }

    @Test
    void createRejectsMixedOwnerGroupsForAdministrator() {
        when(materialParser.parse(any())).thenReturn(List.of(material(1)));
        when(accountGroupMapper.selectById(1L)).thenReturn(group(1L, 11L));
        when(accountGroupMapper.selectById(2L)).thenReturn(group(2L, 22L));

        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.all(99L))) {
            assertThatThrownBy(() -> service.create(request(1L, 2L), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("分组归属不一致");
        }

        verify(accountProtocolLookupService, never()).findRandomOnlineNormalByGroupId(anyLong());
        verify(marketingTaskMapper, never()).insertTask(any());
    }

    @Test
    void startRejectsTaskBackedByForeignMarketingGroupBeforeMutation() {
        MarketingTask task = new MarketingTask();
        task.setId(31L);
        task.setOwnerUserId(11L);
        task.setAccountGroupId(2L);
        when(mapper.selectTaskForUpdateForScope(anyLong(), any())).thenReturn(task);
        when(accountGroupMapper.selectById(2L)).thenReturn(group(2L, 22L));

        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.self(11L))) {
            assertThatThrownBy(() -> service.start(31L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("分组不存在");
        }

        verify(mapper, never()).startTask(anyLong(), anyLong());
        verify(groupOccupancyService, never()).tryLock(anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void administratorCannotStartHistoricalUnownedTask() {
        MarketingTask task = new MarketingTask();
        task.setId(31L);
        task.setAccountGroupId(2L);
        when(mapper.selectTaskForUpdateForScope(anyLong(), any())).thenReturn(task);
        when(accountGroupMapper.selectById(2L)).thenReturn(group(2L, null));

        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.all(99L))) {
            assertThatThrownBy(() -> service.start(31L))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            assertThat(ex.getCode()).isEqualTo(ErrorCode.ACCESS_DENIED.code()));
        }

        verify(mapper, never()).selectTaskById(anyLong());
        verify(mapper, never()).startTask(anyLong(), anyLong());
        verify(groupOccupancyService, never()).tryLock(anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void administratorCannotCreateTaskWithDifferentGroupAndTemplateOwners() {
        when(materialParser.parse(any())).thenReturn(List.of(material(1)));
        when(accountGroupMapper.selectById(1L)).thenReturn(group(1L, 11L));
        when(accountGroupMapper.selectById(2L)).thenReturn(group(2L, 11L));
        when(accountProtocolLookupService.findRandomOnlineNormalByGroupId(anyLong()))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(
                        com.armada.platform.protocol.model.command.ProtocolAccountRef.class)));
        MarketingTemplate template = new MarketingTemplate();
        template.setId(3L);
        template.setOwnerUserId(22L);
        when(templateMapper.selectByIdForUpdate(anyLong(), any())).thenReturn(template);

        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.all(99L))) {
            assertThatThrownBy(() -> service.create(request(1L, 2L), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("分组与模板归属不一致");
        }

        verify(marketingTaskMapper, never()).insertTask(any());
    }

    @Test
    void administratorCannotCreateTaskOnBehalfOfAnotherOwner() {
        when(materialParser.parse(any())).thenReturn(List.of(material(1)));
        when(accountGroupMapper.selectById(1L)).thenReturn(group(1L, 22L));
        when(accountGroupMapper.selectById(2L)).thenReturn(group(2L, 22L));
        when(accountProtocolLookupService.findRandomOnlineNormalByGroupId(anyLong()))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(
                        com.armada.platform.protocol.model.command.ProtocolAccountRef.class)));
        MarketingTemplate template = new MarketingTemplate();
        template.setId(3L);
        template.setOwnerUserId(22L);
        when(templateMapper.selectByIdForUpdate(anyLong(), any())).thenReturn(template);

        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.all(99L))) {
            assertThatThrownBy(() -> service.create(request(1L, 2L), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("只能使用当前操作者自己的资源");
        }

        verify(marketingTaskMapper, never()).insertTask(any());
    }

    private static CreateGroupPullMarketingTaskDTO request(Long builderGroupId, Long marketingGroupId) {
        return new CreateGroupPullMarketingTaskDTO(
                "归属校验",
                builderGroupId,
                null,
                null,
                marketingGroupId,
                10,
                3L,
                30,
                null,
                3,
                3,
                60,
                1,
                true,
                null,
                System.currentTimeMillis() + 60_000L);
    }

    private static AccountGroup group(Long id, Long ownerUserId) {
        AccountGroup group = new AccountGroup();
        group.setId(id);
        group.setOwnerUserId(ownerUserId);
        return group;
    }

    private static GroupPullMarketingMaterialParser.ParsedMaterial material(int lineNo) {
        return new GroupPullMarketingMaterialParser.ParsedMaterial(lineNo, "8613900000000");
    }
}
