package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.dto.PullTaskDirectLinkCreateDTO;
import com.armada.task.model.dto.PullTaskStandardDataPackagesDTO;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.service.impl.PullTaskStandardCreateResources;
import com.armada.task.service.impl.PullTaskStandardCreateTransactionService;
import com.armada.task.service.impl.PullTaskStandardDraftServiceImpl;
import com.armada.task.service.impl.PullTaskStandardDraftSources;
import com.armada.task.service.impl.PullTaskStandardDraftWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 防止新模式借旧草稿入口或隐藏字段恢复管理链。 */
class PullTaskDirectLinkContractTest {
    @Test
    void oldDraftEndpointsRejectDirectLinkBeforeWritingAnything() {
        var writer = mock(PullTaskStandardDraftWriter.class);
        var drafts = new PullTaskStandardDraftServiceImpl(mock(PullTaskMapper.class),
                mock(PullTaskGroupExecutionMapper.class), writer, new PullTaskMaterialTxtParser(),
                new PullTaskStandardDraftSources(null, null, null));

        assertThatThrownBy(() -> drafts.plan(PullTaskCreationMode.DIRECT_LINK, null, "link", List.of(), 2L, "operator"))
                .hasMessageContaining("无草稿创建入口");
        assertThatThrownBy(() -> drafts.planDataPackages(new PullTaskStandardDataPackagesDTO(
                PullTaskCreationMode.DIRECT_LINK, List.of(1L), null, "link"), 2L, "operator"))
                .hasMessageContaining("无草稿创建入口");
        verifyNoInteractions(writer);
    }

    @Test
    void oldSubmitCannotFreezeDirectLinkAsDraft() {
        var tasks = mock(PullTaskMapper.class);
        var transactions = new PullTaskStandardCreateTransactionService(tasks,
                mock(PullTaskGroupExecutionMapper.class), new PullTaskStandardCreateResources(null, null, null, null, null));
        var request = new PullTaskDirectLinkCreateDTO(UUID.randomUUID().toString(), "新模式", null, 0,
                null, "link", List.of(), 1, 2, 3, 5, 15, 2, 0, 1, 12L, null, null);

        assertThatThrownBy(() -> transactions.submit(request.frozenSettings(), 2L)).hasMessageContaining("无草稿创建入口");
        verifyNoInteractions(tasks);
    }

    @Test
    void rejectsManagerAndDraftFieldsInDirectRequestJson() {
        var json = new ObjectMapper();
        for (String field : List.of("draftTaskId", "managerGroupId", "groupSetting", "materialAdminTiming")) {
            assertThatThrownBy(() -> json.readValue("{\"" + field + "\":1}", PullTaskDirectLinkCreateDTO.class))
                    .hasMessageContaining("不支持的新群链接任务字段");
        }
    }
}
