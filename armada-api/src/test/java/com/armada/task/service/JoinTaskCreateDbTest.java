package com.armada.task.service;

import com.armada.boot.Application;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.dto.CreateJoinTaskRequest;
import com.armada.task.model.dto.SelectedAccount;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.model.vo.JoinTaskVO;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 进群任务建任务 service 真库集成测试。
 * 继承 DbTestBase:@Transactional 每用例回滚,TenantContext 预置租户 1。
 *
 * <p>本类覆盖 @SpringBootTest 显式置 spring.flyway.enabled=false:共享脏 checkout 中有
 * 他人在途、未跟踪的迁移(协议层重构期),启动 flyway 会把这些 WIP 迁移应用进本机测试库。
 * 本测试只验建任务业务逻辑,join_task/join_task_result schema 已由前序 DbTest 的 V007 建好,
 * 故跳过 flyway 与他人 WIP 解耦。待迁移稳定后可移除此覆盖、回归 DbTestBase 默认(flyway 开)。</p>
 */
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.flyway.enabled=false")
class JoinTaskCreateDbTest extends DbTestBase {

    @Autowired
    private JoinTaskService service;

    @Autowired
    private JoinTaskMapper joinTaskMapper;

    @Autowired
    private JoinTaskResultMapper resultMapper;

    // 两条真实 WA 群链接
    private static final String LINK1 = "https://chat.whatsapp.com/AAABBBCCC111";
    private static final String LINK2 = "https://chat.whatsapp.com/DDDEEEFFF222";

    /**
     * 用例 1:方式一轮询分配 + 计数校验。
     * FIXED_ACCOUNTS_PER_LINK, accountsPerLink=2, 2条有效链接, 3账号
     * → VO total=4, pending=4, status=DRAFT, intervalLabel="10-20s"
     * → 4条明细 account=[911,922,933,911] accountId=[1,2,3,1] 全PENDING
     */
    @Test
    void case1_fixedAccountsPerLink_countAndRows() {
        CreateJoinTaskRequest req = new CreateJoinTaskRequest(
                "方式一测试任务",
                List.of(10L),
                List.of("测试分组A"),
                List.of(new SelectedAccount(1L, "911"),
                        new SelectedAccount(2L, "922"),
                        new SelectedAccount(3L, "933")),
                LINK1 + "\n" + LINK2,
                "FIXED_ACCOUNTS_PER_LINK",
                2, null, null,
                10, 20, null, null,
                false, 0, "SKIP");

        JoinTaskVO vo = service.createTask(req);

        assertThat(vo.total()).isEqualTo(4);
        assertThat(vo.pending()).isEqualTo(4);
        assertThat(vo.executed()).isEqualTo(0);
        assertThat(vo.success()).isEqualTo(0);
        assertThat(vo.failed()).isEqualTo(0);
        assertThat(vo.status()).isEqualTo("DRAFT");
        assertThat(vo.intervalLabel()).isEqualTo("10-20s");
        assertThat(vo.accountGroupNames()).isEqualTo("测试分组A");

        List<JoinTaskResult> rows = resultMapper.selectResultsByTask(vo.id());
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(JoinTaskResult::getAccount)
                .containsExactly("911", "922", "933", "911");
        assertThat(rows).extracting(JoinTaskResult::getAccountId)
                .containsExactly(1L, 2L, 3L, 1L);
        assertThat(rows).extracting(JoinTaskResult::getStatus)
                .containsOnly("PENDING");
    }

    /**
     * 用例 2:方式二固定账号多链接。
     * FIXED_ACCOUNT_MULTI_LINK, executorAccountCount=2, linksPerAccount=3, 2条有效链接(linkCap=min(3,2)=2)
     * → total=4, 4行 (911,L1)(911,L2)(922,L1)(922,L2)
     */
    @Test
    void case2_fixedAccountMultiLink_rows() {
        CreateJoinTaskRequest req = new CreateJoinTaskRequest(
                "方式二测试任务",
                null, null,
                List.of(new SelectedAccount(1L, "911"),
                        new SelectedAccount(2L, "922")),
                LINK1 + "\n" + LINK2,
                "FIXED_ACCOUNT_MULTI_LINK",
                null, 2, 3,
                null, null, 5, 15,
                true, 2, "RETRY");

        JoinTaskVO vo = service.createTask(req);

        assertThat(vo.total()).isEqualTo(4);
        assertThat(vo.pending()).isEqualTo(4);

        List<JoinTaskResult> rows = resultMapper.selectResultsByTask(vo.id());
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(JoinTaskResult::getAccount)
                .containsExactly("911", "911", "922", "922");
        assertThat(rows).extracting(JoinTaskResult::getLink)
                .containsExactly(LINK1, LINK2, LINK1, LINK2);
        assertThat(rows).extracting(JoinTaskResult::getStatus)
                .containsOnly("PENDING");
    }

    /**
     * 用例 3:无效链接不计入 total。
     * linksText = 1条有效 + 1条无效 "not-a-link"
     * → 明细 2行(1 PENDING + 1 FAILED reason="非群链接"), VO total=1, pending=1
     */
    @Test
    void case3_invalidLinkNotCountedInTotal() {
        CreateJoinTaskRequest req = new CreateJoinTaskRequest(
                "无效链接测试",
                null, null,
                List.of(new SelectedAccount(1L, "911")),
                LINK1 + "\nnot-a-link",
                "FIXED_ACCOUNTS_PER_LINK",
                1, null, null,
                5, 10, null, null,
                false, 0, "SKIP");

        JoinTaskVO vo = service.createTask(req);

        assertThat(vo.total()).isEqualTo(1);
        assertThat(vo.pending()).isEqualTo(1);

        List<JoinTaskResult> rows = resultMapper.selectResultsByTask(vo.id());
        assertThat(rows).hasSize(2);

        long pendingCount = rows.stream().filter(r -> "PENDING".equals(r.getStatus())).count();
        long failedCount = rows.stream().filter(r -> "FAILED".equals(r.getStatus())).count();
        assertThat(pendingCount).isEqualTo(1);
        assertThat(failedCount).isEqualTo(1);

        JoinTaskResult failedRow = rows.stream()
                .filter(r -> "FAILED".equals(r.getStatus()))
                .findFirst().orElseThrow();
        assertThat(failedRow.getReason()).isEqualTo("非群链接");
    }

    /**
     * 用例 4:name 空白字符串抛 VALIDATION 业务异常。
     */
    @Test
    void case4_blankNameThrowsValidation() {
        CreateJoinTaskRequest req = new CreateJoinTaskRequest(
                " ",
                null, null, null, null, null,
                null, null, null,
                null, null, null, null,
                false, 0, null);

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex ->
                        assertThat(((BusinessException) ex).getCode())
                                .isEqualTo(ErrorCode.VALIDATION.code()));
    }

    /**
     * 用例 5:selected_account_ids 和 account_group_ids 正确落库(JSON 快照)。
     */
    @Test
    void case5_selectedAccountIdsAndGroupIdsPersisted() {
        CreateJoinTaskRequest req = new CreateJoinTaskRequest(
                "快照落库测试",
                List.of(1L, 2L, 3L),
                List.of("G1", "G2", "G3"),
                List.of(new SelectedAccount(1L, "911"),
                        new SelectedAccount(2L, "922"),
                        new SelectedAccount(3L, "933")),
                LINK1 + "\n" + LINK2,
                "FIXED_ACCOUNTS_PER_LINK",
                2, null, null,
                10, 20, null, null,
                false, 0, "SKIP");

        JoinTaskVO vo = service.createTask(req);

        JoinTask taskInDb = joinTaskMapper.selectByTenantAndId(vo.id());
        assertThat(taskInDb).isNotNull();
        assertThat(taskInDb.getSelectedAccountIds()).isEqualTo("[1,2,3]");
        assertThat(taskInDb.getAccountGroupIds()).isEqualTo("[1,2,3]");
    }

    /**
     * 用例 6:跨租户隔离 — tenant=2 查不到 tenant=1 的任务。
     */
    @Test
    void case6_crossTenantIsolation() {
        // 在租户 1 下建任务(BeforeEach 已 set(1L))
        CreateJoinTaskRequest req = new CreateJoinTaskRequest(
                "租户隔离测试",
                null, null,
                List.of(new SelectedAccount(1L, "911")),
                LINK1,
                "FIXED_ACCOUNTS_PER_LINK",
                1, null, null,
                5, 10, null, null,
                false, 0, "SKIP");

        JoinTaskVO vo = service.createTask(req);
        Long taskId = vo.id();

        // 切换到租户 2,应查不到
        TenantContext.set(2L);
        try {
            JoinTask result = joinTaskMapper.selectByTenantAndId(taskId);
            assertThat(result).isNull();
        } finally {
            // 复位租户 1(AfterEach 会 clear,保险起见也显式复位)
            TenantContext.set(1L);
        }
    }
}
