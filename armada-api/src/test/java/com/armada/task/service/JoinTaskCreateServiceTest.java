package com.armada.task.service;

import com.armada.group.service.GroupLinkRegistryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.dto.CreateJoinTaskDTO;
import com.armada.task.model.dto.SelectedAccount;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.service.impl.JoinTaskServiceImpl;
import com.armada.task.worker.JoinTaskWorker;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JoinTaskCreateServiceTest {

    @Mock
    private JoinTaskMapper joinTaskMapper;

    @Mock
    private JoinTaskResultMapper resultMapper;

    @Mock
    private GroupLinkRegistryService groupLinkRegistryService;

    @Mock
    private JoinTaskWorker joinTaskWorker;

    private JoinTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);
        service = new JoinTaskServiceImpl(joinTaskMapper, resultMapper, groupLinkRegistryService, joinTaskWorker);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createTask_rejectsWhenNoValidLinksRemainAfterCleaning() {
        CreateJoinTaskDTO req = new CreateJoinTaskDTO(
                "无有效链接校验",
                null,
                null,
                List.of(new SelectedAccount(1L, "911")),
                "\n   \n",
                "FIXED_ACCOUNTS_PER_LINK",
                1,
                null,
                null,
                5,
                10,
                null,
                null,
                false,
                0,
                "SKIP");

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(be.getMessage()).contains("当前没有有效群链接");
                });

        verify(joinTaskMapper, never()).insert(any(JoinTask.class));
        verify(resultMapper, never()).insertResults(any());
        verify(groupLinkRegistryService, never()).registerJoinTaskTargets(any());
    }
}
