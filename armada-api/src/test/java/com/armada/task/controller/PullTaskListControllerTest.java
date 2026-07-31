package com.armada.task.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.task.model.dto.PullTaskIdsDTO;
import com.armada.task.model.dto.PullTaskQuery;
import com.armada.task.model.vo.PullTaskListVO;
import com.armada.task.service.PullTaskListService;
import com.armada.task.service.PullTaskMutationService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 拉群任务统一列表 Controller 委托合同测试。 */
class PullTaskListControllerTest {

    @Test
    void listDelegatesTypedQueryAndReturnsSuccessEnvelope() {
        PullTaskListService service = mock(PullTaskListService.class);
        PullTaskMutationService mutationService = mock(PullTaskMutationService.class);
        PullTaskListController controller = new PullTaskListController(service, mutationService);
        PullTaskQuery query = new PullTaskQuery();
        PageResult<PullTaskListVO> page = PageResult.of(List.of(), 1, 10, 0);
        when(service.list(query)).thenReturn(page);

        ApiResponse<PageResult<PullTaskListVO>> response = controller.list(query);

        verify(service).list(query);
        assertThat(response.code()).isZero();
        assertThat(response.data()).isSameAs(page);
    }

    @Test
    void batchDeleteDelegatesTypedIdsToMutationService() {
        PullTaskListService listService = mock(PullTaskListService.class);
        PullTaskMutationService mutationService = mock(PullTaskMutationService.class);
        PullTaskListController controller =
                new PullTaskListController(listService, mutationService);
        PullTaskIdsDTO request = new PullTaskIdsDTO(List.of(11L, 12L));
        when(mutationService.batchDelete(request.ids())).thenReturn(1);

        ApiResponse<Integer> response = controller.batchDelete(request);

        verify(mutationService).batchDelete(request.ids());
        assertThat(response.code()).isZero();
        assertThat(response.data()).isEqualTo(1);
    }
}
