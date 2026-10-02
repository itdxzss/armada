package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.group.service.GroupFolderService;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskDirectLinkCreateDTO;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.service.impl.PullTaskDataPackageSourceService;
import com.armada.task.service.impl.PullTaskDirectLinkPlanner;
import com.armada.task.service.impl.PullTaskStandardDraftSources;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** 直接链接创建仅解析来源，A 标记不再成为提权待办。 */
class PullTaskDirectLinkPlannerTest {
    private final PullTaskGroupExecutionMapper executions = mock(PullTaskGroupExecutionMapper.class);
    private final PullTaskLinkProbeService probes = mock(PullTaskLinkProbeService.class);
    private final PullTaskDirectLinkPlanner planner = new PullTaskDirectLinkPlanner(
            new PullTaskMaterialTxtParser(), executions, new PullTaskStandardDraftSources(
                    probes, mock(GroupFolderService.class), mock(PullTaskDataPackageSourceService.class)));

    @Test
    void directLinkIsLinkSourceAndNeverNeedsManagerSettings() {
        assertThat(PullTaskCreationMode.DIRECT_LINK.isDirectLink()).isTrue();
        assertThat(PullTaskCreationMode.DIRECT_LINK.isLinkMode()).isTrue();
        assertThat(PullTaskCreationMode.DIRECT_LINK.usesSelectedGroupFolder(2L)).isTrue();
        assertThat(PullTaskCreationMode.DIRECT_LINK.usesSelectedGroupFolder(null)).isFalse();
        var settings = request().frozenSettings();
        assertThat(settings.draftTaskId()).isNull();
        assertThat(settings.managerGroupId()).isNull();
        assertThat(settings.groupSetting().enabled()).isFalse();
        assertThat(settings.pullerJoinByLink()).isTrue();
        assertThat(settings.creatorLeaveAfterPull()).isFalse();
    }

    @Test
    void aMarkedNumbersRemainOrdinaryMembers() {
        String link = "https://chat.whatsapp.com/ABCDEFGHIJKLMNOPQRSTUV";
        when(executions.selectOccupiedLinks(any())).thenReturn(List.of());
        when(probes.probe(any(), any())).thenReturn(new PullTaskLinkProbeService.ProbeResult(List.of(), List.of(link)));
        var file = new MockMultipartFile("files", "members.txt", "text/plain", "919876543210A\n919876543211".getBytes());

        var plan = planner.plan(request(), List.of(file));

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).members()).extracting(m -> m.getNormalizedPhone())
                .containsExactly("919876543210", "919876543211");
        assertThat(plan.get(0).members()).allSatisfy(m -> assertThat(m.getAdminRequired()).isZero());
    }

    @Test
    void noMaterialsFailsBeforeAnyTaskIsWritten() {
        assertThatThrownBy(() -> planner.plan(request(), List.of())).hasMessageContaining("料子");
    }

    private PullTaskDirectLinkCreateDTO request() {
        return new PullTaskDirectLinkCreateDTO(UUID.randomUUID().toString(), "直接拉群", null, 0,
                null, "https://chat.whatsapp.com/ABCDEFGHIJKLMNOPQRSTUV", List.of(),
                1, 2, 3, 5, 15, 2, 0, 1, 12L, null, null);
    }
}
