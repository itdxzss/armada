package com.armada.task.service;

import com.armada.boot.Application;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.dto.CreateJoinTaskDTO;
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
 * 本测试只验建任务业务逻辑及本地 group_link 登记副作用,相关 schema 已由前序 DbTest 迁移建好,
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

    @Autowired
    private GroupLinkMapper groupLinkMapper;

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
        CreateJoinTaskDTO req = new CreateJoinTaskDTO(
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
        CreateJoinTaskDTO req = new CreateJoinTaskDTO(
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
     * 用例 3:进群任务保存前执行群链接格式校验,任一无效链接直接抛 VALIDATION。
     */
    @Test
    void case3_invalidLinkThrowsValidation() {
        CreateJoinTaskDTO req = new CreateJoinTaskDTO(
                "无效链接测试",
                null, null,
                List.of(new SelectedAccount(1L, "911")),
                LINK1 + "\nnot-a-link",
                "FIXED_ACCOUNTS_PER_LINK",
                1, null, null,
                5, 10, null, null,
                false, 0, "SKIP");

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(be.getMessage()).contains("https://chat.whatsapp.com/");
                });
    }

    /**
     * 用例 4:name 空白字符串抛 VALIDATION 业务异常。
     */
    @Test
    void case4_blankNameThrowsValidation() {
        CreateJoinTaskDTO req = new CreateJoinTaskDTO(
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
     * 用例 4.1:进群任务保存时群链接必须显式使用 https:// 开头,裸 chat.whatsapp.com 不允许保存。
     */
    @Test
    void case4_1_linkWithoutHttpsSchemeThrowsValidation() {
        CreateJoinTaskDTO req = new CreateJoinTaskDTO(
                "缺少 https 校验",
                null, null,
                List.of(new SelectedAccount(1L, "911")),
                "chat.whatsapp.com/NoHttpsScheme",
                "FIXED_ACCOUNTS_PER_LINK",
                1, null, null,
                5, 10, null, null,
                false, 0, "SKIP");

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(be.getMessage()).contains("https://");
                });
    }

    /**
     * 用例 5:selected_account_ids 和 account_group_ids 正确落库(JSON 快照)。
     */
    @Test
    void case5_selectedAccountIdsAndGroupIdsPersisted() {
        CreateJoinTaskDTO req = new CreateJoinTaskDTO(
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
        CreateJoinTaskDTO req = new CreateJoinTaskDTO(
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
            // join_task_result 也被租户拦截器隔离
            assertThat(resultMapper.selectResultsByTask(taskId)).isEmpty();
        } finally {
            // 复位租户 1(AfterEach 会 clear,保险起见也显式复位)
            TenantContext.set(1L);
        }
    }

    /**
     * 用例 7:建进群任务时,仅严格合法的群邀请链接登记到群组池。
     *
     * <p>登记只写本地 group_link:新入口来源为 JOIN_TASK,关系态为 TARGET,不绑定导入分组/批次。
     * 同一归一化链接在输入框重复出现时只登记一次。</p>
     */
    @Test
    void case7_createTask_registersValidLinksAsJoinTaskTargets() {
        CreateJoinTaskDTO req = new CreateJoinTaskDTO(
                "群组池登记测试",
                null, null,
                List.of(new SelectedAccount(1L, "911")),
                "HTTPS://CHAT.WHATSAPP.COM/CreateRegistryA/\n"
                        + "https://chat.whatsapp.com/CreateRegistryA",
                "FIXED_ACCOUNTS_PER_LINK",
                1, null, null,
                5, 10, null, null,
                false, 0, "SKIP");

        service.createTask(req);

        GroupLink registered = groupLinkMapper.selectAnyByUrl("chat.whatsapp.com/CreateRegistryA");
        assertThat(registered).isNotNull();
        assertThat(registered.getOrigin()).isEqualTo(GroupLinkOrigin.JOIN_TASK.code());
        assertThat(registered.getMembershipState()).isEqualTo(GroupMembershipState.TARGET.code());
        assertThat(registered.getLabelId()).isNull();
        assertThat(registered.getImportBatchId()).isNull();
        assertThat(groupLinkMapper.selectAnyByUrl("chat.whatsapp.com/")).isNull();
    }

    /**
     * 用例 8:建进群任务登记到已软删的群链接时,复活原 group_link 行,不新插第二条。
     */
    @Test
    void case8_createTask_revivesSoftDeletedGroupLinkTarget() {
        GroupLink existing = new GroupLink();
        existing.setLinkUrl("chat.whatsapp.com/ReviveJoinTaskTarget");
        existing.setOrigin(GroupLinkOrigin.JOIN_TASK.code());
        existing.setMembershipState(GroupMembershipState.TARGET.code());
        long now = System.currentTimeMillis();
        existing.setCreatedAt(now);
        existing.setUpdatedAt(now);
        groupLinkMapper.insert(existing);
        groupLinkMapper.softDeleteByIds(List.of(existing.getId()), now + 1);

        CreateJoinTaskDTO req = new CreateJoinTaskDTO(
                "复活登记测试",
                null, null,
                List.of(new SelectedAccount(1L, "911")),
                "https://chat.whatsapp.com/ReviveJoinTaskTarget",
                "FIXED_ACCOUNTS_PER_LINK",
                1, null, null,
                5, 10, null, null,
                false, 0, "SKIP");

        service.createTask(req);

        GroupLink revived = groupLinkMapper.selectAnyByUrl("chat.whatsapp.com/ReviveJoinTaskTarget");
        assertThat(revived).isNotNull();
        assertThat(revived.getId()).isEqualTo(existing.getId());
        assertThat(revived.getDeletedAt()).isNull();
        assertThat(revived.getOrigin()).isEqualTo(GroupLinkOrigin.JOIN_TASK.code());
        assertThat(revived.getMembershipState()).isEqualTo(GroupMembershipState.TARGET.code());
    }
}
