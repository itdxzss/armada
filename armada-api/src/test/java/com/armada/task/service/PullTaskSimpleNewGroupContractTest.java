package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.group.service.GroupFolderService;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.dto.PullTaskSimpleNewGroupCreateDTO;
import com.armada.task.model.dto.PullTaskStandardDataPackagesDTO;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.service.impl.PullTaskDataPackageSourceService;
import com.armada.task.service.impl.PullTaskDirectLinkPlanner;
import com.armada.task.service.impl.PullTaskStandardCreateResources;
import com.armada.task.service.impl.PullTaskStandardCreateTransactionService;
import com.armada.task.service.impl.PullTaskStandardDraftServiceImpl;
import com.armada.task.service.impl.PullTaskStandardDraftSources;
import com.armada.task.service.impl.PullTaskStandardDraftWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** 新模式保留建群/接管边界，但旧草稿与已移除行为不能绕过独立合同。 */
class PullTaskSimpleNewGroupContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void plansOneNewGroupPerMaterialWithoutReadingLinksAndNeverPromotesMarkedNumbers() {
        var links = mock(PullTaskLinkProbeService.class);
        var executions = mock(PullTaskGroupExecutionMapper.class);
        var folders = mock(GroupFolderService.class);
        var planner = new PullTaskDirectLinkPlanner(new PullTaskMaterialTxtParser(), executions,
                new PullTaskStandardDraftSources(links, folders, mock(PullTaskDataPackageSourceService.class)));
        var file = new MockMultipartFile("files", "a.txt", "text/plain",
                "919876543210A\n919876543211".getBytes(StandardCharsets.UTF_8));
        var rows = planner.plan(request(), List.of(file, file));
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.execution().getNormalizedLink()).isNull();
            assertThat(row.members()).hasSize(2).allSatisfy(member -> assertThat(member.getAdminRequired()).isZero());
        });
        verifyNoInteractions(links, executions, folders);
    }

    @Test
    void requiresCreatorManagerAndSupportedPullParametersButAllowsEmptyDescription() throws Exception {
        PullTaskDirectLinkPlanner.validate(request());
        for (String field : List.of("creatorGroupId", "managerGroupId")) {
            ObjectNode node = json.valueToTree(request());
            node.putNull(field);
            var invalid = json.treeToValue(node, PullTaskSimpleNewGroupCreateDTO.class);
            assertThatThrownBy(() -> PullTaskDirectLinkPlanner.validate(invalid)).hasMessageContaining("分组");
        }
        ObjectNode node = json.valueToTree(request());
        node.put("earlyPullCallCount", 1);
        var invalid = json.treeToValue(node, PullTaskSimpleNewGroupCreateDTO.class);
        assertThatThrownBy(() -> PullTaskDirectLinkPlanner.validate(invalid)).hasMessageContaining("参数");
        assertThat(request().frozenSettings().creatorLeaveAfterPull()).isFalse();
        assertThat(request().frozenSettings().clearExistingMembers()).isFalse();
        assertThat(request().frozenSettings().creatorDeleteAfterTakeover()).isFalse();
    }

    @Test
    void rejectsRemovedFieldsAndRejectsBothOldDraftRoutes() {
        for (String field : List.of("draftTaskId", "linksText", "groupFolderId", "groupSetting",
                "materialAdminTiming", "clearExistingMembers", "creatorLeaveAfterPull", "initialStationCount")) {
            assertThatThrownBy(() -> json.readValue("{\"" + field + "\":1}", PullTaskSimpleNewGroupCreateDTO.class))
                    .hasMessageContaining("不支持的新群模式");
        }
        var writer = mock(PullTaskStandardDraftWriter.class);
        var drafts = new PullTaskStandardDraftServiceImpl(mock(PullTaskMapper.class),
                mock(PullTaskGroupExecutionMapper.class), writer, new PullTaskMaterialTxtParser(),
                new PullTaskStandardDraftSources(null, null, null));
        assertThatThrownBy(() -> drafts.plan(PullTaskCreationMode.SIMPLE_NEW_GROUP, null, "", List.of(), 2L, "operator"))
                .hasMessageContaining("无草稿创建入口");
        assertThatThrownBy(() -> drafts.planDataPackages(new PullTaskStandardDataPackagesDTO(
                PullTaskCreationMode.SIMPLE_NEW_GROUP, List.of(1L), null, ""), 2L, "operator"))
                .hasMessageContaining("无草稿创建入口");
        var tasks = mock(PullTaskMapper.class);
        var transactions = new PullTaskStandardCreateTransactionService(tasks,
                mock(PullTaskGroupExecutionMapper.class), new PullTaskStandardCreateResources(null, null, null, null, null));
        assertThatThrownBy(() -> transactions.submit(request().frozenSettings(), 2L)).hasMessageContaining("无草稿创建入口");
        verifyNoInteractions(writer, tasks);
    }

    private PullTaskSimpleNewGroupCreateDTO request() {
        return new PullTaskSimpleNewGroupCreateDTO(UUID.randomUUID().toString(), "精简新群", null, 0,
                List.of(), 1, 0, 1, 3, 10, 2, 0, 1, 12L, null, null,
                13L, 14L, null, null, "业务群", null, null, 15);
    }
}
