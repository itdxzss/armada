package com.armada.marketing.script;

import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountService;
import com.armada.group.model.vo.GroupDetailVO;
import com.armada.group.service.GroupDetailService;
import com.armada.marketing.converter.ScriptMarketingConverter;
import com.armada.marketing.mapper.ScriptMarketingGroupMapper;
import com.armada.marketing.mapper.ScriptMarketingSendRecordMapper;
import com.armada.marketing.mapper.ScriptMarketingTaskMapper;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.dto.ScriptMarketingSaveDTO;
import com.armada.marketing.model.dto.ScriptMarketingStepDTO;
import com.armada.marketing.model.vo.ScriptQualificationVO;
import com.armada.marketing.script.service.ScriptMarketingContentService;
import com.armada.marketing.script.service.ScriptMarketingTaskService;
import com.armada.marketing.script.service.ScriptQualificationService;
import com.armada.marketing.service.MarketingTemplateFileService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 任务保存与检查共用的表单入口允许自动管理员，拒绝新草稿写入固定身份。 */
class ScriptMarketingTaskAssignmentTest {
    final ScriptMarketingContentService content = mock(ScriptMarketingContentService.class);
    final GroupDetailService groupDetails = mock(GroupDetailService.class);
    final ScriptQualificationService qualification = mock(ScriptQualificationService.class);
    final ScriptMarketingTaskService service = new ScriptMarketingTaskService(
            mock(ScriptMarketingTaskMapper.class), mock(ScriptMarketingGroupMapper.class),
            mock(ScriptMarketingSendRecordMapper.class), Mappers.getMapper(ScriptMarketingConverter.class),
            content, groupDetails, mock(MarketingTemplateFileService.class), mock(AccountGroupService.class),
            qualification, mock(AccountService.class));

    @Test void unboundAdminPassesFormValidationAndChecksTargetGroups() {
        var dto = dto(null);
        var group = mock(GroupDetailVO.class);
        when(group.groupJid()).thenReturn("120040@g.us");
        when(group.groupName()).thenReturn("测试群");
        when(groupDetails.detail(40L)).thenReturn(group);
        var report = new ScriptQualificationVO(true, 6, 1, null, 1, List.of());
        when(qualification.inspect(eq(30L), eq(dto.steps()), anyList(), eq(false)))
                .thenReturn(new ScriptQualificationService.Preparation(report, Map.of()));
        assertThat(service.check(dto)).isSameAs(report);
        verify(content).encode(dto.steps());
    }
    @Test void newDraftCannotChooseAFixedAdministrator() {
        assertThatThrownBy(() -> service.create(dto(1L), 11L)).hasMessageContaining("无需手动选择账号");
        verifyNoInteractions(groupDetails, qualification);
    }
    private ScriptMarketingSaveDTO dto(Long accountId) {
        var message = new MarketingTemplateDTO("", 1, null, null, "hello", null, null, null, null, false);
        return new ScriptMarketingSaveDTO("自动管理员任务", 10, null, null, List.of(40L), List.of(
                new ScriptMarketingStepDTO("ADMIN", accountId, message, "A", 0, 0),
                new ScriptMarketingStepDTO("PROMOTER", null, message, "P1", 10, 10)), 30L);
    }
}
