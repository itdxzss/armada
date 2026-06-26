package com.armada.task.service;

import com.armada.boot.Application;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.model.dto.CreateJoinTaskRequest;
import com.armada.task.model.dto.SelectedAccount;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.enums.DistributionMode;
import com.armada.task.model.enums.JoinTaskStatus;
import com.armada.task.model.vo.JoinResultRowVO;
import com.armada.task.model.vo.JoinTaskDetailVO;
import com.armada.task.model.vo.JoinTaskVO;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 进群任务变更路径真库集成测试:编辑(仅 DRAFT)+ 批量软删。
 *
 * <p>继承 DbTestBase:@Transactional 每用例回滚,TenantContext 预置租户 1。
 * 使用 spring.flyway.enabled=false 与在途 WIP 迁移解耦(schema 由 V007 已建好)。</p>
 *
 * <p>⚠️ MyBatis 一级缓存陷阱说明:guard 触发用例改用 {@link #insertRawTask} 通过
 * joinTaskMapper.insert 直接造行(insert 会刷新缓存,status/executed 可直接控制),
 * 不使用 JdbcTemplate 旁路改——后者在同事务内会被一级缓存遮蔽导致 guard 不触发。</p>
 */
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.flyway.enabled=false")
class JoinTaskMutationDbTest extends DbTestBase {

    @Autowired
    private JoinTaskService service;

    @Autowired
    private JoinTaskMapper joinTaskMapper;

    // 真实 WA 群链接格式
    private static final String LINK1 = "https://chat.whatsapp.com/AAABBBCCC111";
    private static final String LINK2 = "https://chat.whatsapp.com/DDDEEEFFF222";
    private static final String LINK3 = "https://chat.whatsapp.com/GGGHHHIII333";

    // =========================================================================
    // 造数辅助
    // =========================================================================

    /**
     * 通过 joinTaskMapper.insert 直接插入一行,status/executed 可控。
     * 用于 guard 触发用例:insert 会刷新 MyBatis 一级缓存,避免用 JdbcTemplate 旁路改被缓存遮蔽。
     */
    private Long insertRawTask(String status, int executed) {
        JoinTask t = new JoinTask();
        t.setName("原始任务");
        t.setAccountGroupIds("[]");
        t.setAccountGroupNames("");
        t.setSelectedAccountIds("[]");
        t.setLinksText("");
        t.setDistributionMode(DistributionMode.FIXED_ACCOUNTS_PER_LINK);
        t.setAccountsPerLink(0);
        t.setExecutorAccountCount(0);
        t.setLinksPerAccount(0);
        t.setFixedIntervalMinSec(0);
        t.setFixedIntervalMaxSec(0);
        t.setMultiIntervalMinSec(0);
        t.setMultiIntervalMaxSec(0);
        t.setIntervalLabel("0-0s");
        t.setRetryEnabled(false);
        t.setRetryLimit(0);
        t.setFailurePolicy("");
        t.setTotal(0);
        t.setExecuted(executed);
        t.setSuccess(0);
        t.setFailed(0);
        t.setPending(0);
        t.setStatus(status);
        long now = System.currentTimeMillis();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        joinTaskMapper.insert(t);
        return t.getId();
    }

    /** 合法的 updateTask 入参(链接可少但 name 非空)。 */
    private static CreateJoinTaskRequest anyValidReq() {
        return new CreateJoinTaskRequest(
                "合法任务名",
                null, null,
                List.of(new SelectedAccount(1L, "911")),
                LINK1,
                "FIXED_ACCOUNTS_PER_LINK",
                1, null, null,
                5, 10, null, null,
                false, 0, "SKIP");
    }

    // =========================================================================
    // 用例 1 — 编辑改配置 + 计划行重建
    // =========================================================================

    /**
     * 用例 1:更新配置后 name/distributionMode 变,计划行按新参数重建。
     *
     * <p>创建:方式一 FIXED_ACCOUNTS_PER_LINK,2 链接 accountsPerLink=2,3 账号 → total=4。
     * 编辑:改为方式二 FIXED_ACCOUNT_MULTI_LINK,2 账号 executorAccountCount=2 linksPerAccount=3,
     * 只 1 条有效链接 → total=2(每账号进1条)。
     * 断言:名称变、distributionMode 变、getDetail.total()=2、results 行数从 4 变 2,且链接为新链接。</p>
     */
    @Test
    void case1_updateTask_configAndPlanRebuilt() {
        // 建任务(方式一,2 链接,accountsPerLink=2,3 账号 → 4 PENDING 行)
        CreateJoinTaskRequest createReq = new CreateJoinTaskRequest(
                "原始任务名",
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
        JoinTaskVO created = service.createTask(createReq);
        assertThat(created.total()).isEqualTo(4);

        // 编辑:改名 + 换方式二 + 只 1 条有效链接
        // executorAccountCount=2,linksPerAccount=3,但只有 1 条链接 → 每账号只进 1 条 → total=2
        CreateJoinTaskRequest updateReq = new CreateJoinTaskRequest(
                "新任务名称",
                List.of(10L),
                List.of("测试分组A"),
                List.of(new SelectedAccount(1L, "911"),
                        new SelectedAccount(2L, "922")),
                LINK3,
                "FIXED_ACCOUNT_MULTI_LINK",
                null, 2, 3,
                null, null, 5, 15,
                true, 2, "RETRY");
        JoinTaskDetailVO detail = service.updateTask(created.id(), updateReq);

        // 配置字段已更新
        assertThat(detail.name()).isEqualTo("新任务名称");
        assertThat(detail.distributionMode()).isEqualTo("FIXED_ACCOUNT_MULTI_LINK");
        assertThat(detail.total()).isEqualTo(2);

        // 计划行重建:行数从 4 变 2,链接为新链接 LINK3
        List<JoinResultRowVO> rows = service.results(created.id());
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(JoinResultRowVO::link).containsOnly(LINK3);

        // getDetail 总数与明细一致
        assertThat(service.getDetail(created.id()).total()).isEqualTo(2);
    }

    // =========================================================================
    // 用例 2 — 已执行(executed>0)不可编辑
    // =========================================================================

    /**
     * 用例 2:executed>0 的 DRAFT 任务不可编辑,抛 VALIDATION。
     *
     * <p>用 insertRawTask(DRAFT, 1) 直接写入 executed=1 的行,绕开 service.createTask 的缓存路径。</p>
     */
    @Test
    void case2_updateTask_executedGtZero_throwsValidation() {
        Long id = insertRawTask(JoinTaskStatus.DRAFT, 1);

        assertThatThrownBy(() -> service.updateTask(id, anyValidReq()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(be.getMessage()).contains("不能编辑");
                });
    }

    // =========================================================================
    // 用例 3 — 非 DRAFT 不可编辑
    // =========================================================================

    /**
     * 用例 3:status=RUNNING(非 DRAFT)不可编辑,抛 VALIDATION。
     *
     * <p>用 insertRawTask(RUNNING, 0) 直接写入 RUNNING 行。</p>
     */
    @Test
    void case3_updateTask_nonDraft_throwsValidation() {
        Long id = insertRawTask(JoinTaskStatus.RUNNING, 0);

        assertThatThrownBy(() -> service.updateTask(id, anyValidReq()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(be.getMessage()).contains("不能编辑");
                });
    }

    // =========================================================================
    // 用例 4 — updateTask NOT_FOUND
    // =========================================================================

    /**
     * 用例 4:updateTask 不存在的 id,抛 NOT_FOUND。
     */
    @Test
    void case4_updateTask_notFound_throwsNotFound() {
        assertThatThrownBy(() -> service.updateTask(99999999L, anyValidReq()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex ->
                        assertThat(((BusinessException) ex).getCode())
                                .isEqualTo(ErrorCode.NOT_FOUND.code()));
    }

    // =========================================================================
    // 用例 5 — batchDelete + 幂等 + 删后 NOT_FOUND
    // =========================================================================

    /**
     * 用例 5:batchDelete 返回实删数;再次删返回 0(幂等);已删任务 getDetail 抛 NOT_FOUND。
     */
    @Test
    void case5_batchDelete_idempotentAndNotFound() {
        JoinTaskVO t1 = service.createTask(anyValidReq());
        JoinTaskVO t2 = service.createTask(new CreateJoinTaskRequest(
                "第二个任务",
                null, null,
                List.of(new SelectedAccount(1L, "922")),
                LINK2,
                "FIXED_ACCOUNTS_PER_LINK",
                1, null, null,
                5, 10, null, null,
                false, 0, "SKIP"));

        // 首次软删 → 返回 2
        int deleted = service.batchDelete(List.of(t1.id(), t2.id()));
        assertThat(deleted).isEqualTo(2);

        // 幂等:再次软删 → 返回 0
        int deletedAgain = service.batchDelete(List.of(t1.id(), t2.id()));
        assertThat(deletedAgain).isEqualTo(0);

        // 已删任务 getDetail 抛 NOT_FOUND
        assertThatThrownBy(() -> service.getDetail(t1.id()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex ->
                        assertThat(((BusinessException) ex).getCode())
                                .isEqualTo(ErrorCode.NOT_FOUND.code()));
    }

    // =========================================================================
    // 用例 6 — batchDelete 空入参
    // =========================================================================

    /**
     * 用例 6:batchDelete(null) 与 batchDelete(List.of()) 均返回 0,不抛异常。
     */
    @Test
    void case6_batchDelete_emptyOrNull_returnsZero() {
        assertThat(service.batchDelete(null)).isEqualTo(0);
        assertThat(service.batchDelete(List.of())).isEqualTo(0);
    }
}
