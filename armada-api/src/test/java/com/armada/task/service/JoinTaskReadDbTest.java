package com.armada.task.service;

import com.armada.boot.Application;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.task.model.dto.CreateJoinTaskRequest;
import com.armada.task.model.dto.JoinTaskQuery;
import com.armada.task.model.dto.SelectedAccount;
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
 * 进群任务读路径真库集成测试。
 * 覆盖 listTasks / intervalOptions / getDetail / results 四个读方法。
 *
 * <p>继承 DbTestBase:@Transactional 每用例回滚,TenantContext 预置租户 1。
 * 使用 spring.flyway.enabled=false 与在途 WIP 迁移解耦(schema 由前序 V007 已建好)。</p>
 */
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.flyway.enabled=false")
class JoinTaskReadDbTest extends DbTestBase {

    @Autowired
    private JoinTaskService service;

    // 真 WA 群链接
    private static final String LINK1 = "https://chat.whatsapp.com/AAA111";
    private static final String LINK2 = "https://chat.whatsapp.com/BBB222";

    // -------------------------------------------------------------------------
    // 造数辅助
    // -------------------------------------------------------------------------

    /** 建方式一任务:甲,2 链接 accountsPerLink=2,3 账号,interval=10-20s。 */
    private JoinTaskVO createJia() {
        CreateJoinTaskRequest req = new CreateJoinTaskRequest(
                "进群任务甲",
                List.of(10L),
                List.of("测试分组A"),
                List.of(new SelectedAccount(1L, "acc1"),
                        new SelectedAccount(2L, "acc2"),
                        new SelectedAccount(3L, "acc3")),
                LINK1 + "\n" + LINK2,
                "FIXED_ACCOUNTS_PER_LINK",
                2, null, null,
                10, 20, null, null,
                false, 0, "SKIP");
        return service.createTask(req);
    }

    /** 建方式二任务:乙,2 链接 executorAccountCount=2 linksPerAccount=3,interval=5-15s。 */
    private JoinTaskVO createYi() {
        CreateJoinTaskRequest req = new CreateJoinTaskRequest(
                "进群任务乙",
                null, null,
                List.of(new SelectedAccount(1L, "acc1"),
                        new SelectedAccount(2L, "acc2")),
                LINK1 + "\n" + LINK2,
                "FIXED_ACCOUNT_MULTI_LINK",
                null, 2, 3,
                null, null, 5, 15,
                false, 0, "SKIP");
        return service.createTask(req);
    }

    // =========================================================================
    // 用例 1 — listTasks 基础验证
    // =========================================================================

    /**
     * 空查询 → total=2,行按 id 降序(乙在前);
     * keyword="甲" → 1 条;
     * distributionMode=FIXED_ACCOUNT_MULTI_LINK → 1(乙);
     * interval="10-20s" → 1(甲);
     * status="DRAFT" → 2;status="DONE" → 0;
     * pageSize=1 → list 1 条 total=2。
     */
    @Test
    void case1_listTasks_basicFilters() {
        JoinTaskVO jia = createJia();
        JoinTaskVO yi = createYi();

        // 空查询
        JoinTaskQuery q = new JoinTaskQuery();
        PageResult<JoinTaskVO> all = service.listTasks(q);
        assertThat(all.total()).isEqualTo(2);
        // id 降序:乙 id 大于甲
        assertThat(all.list().get(0).id()).isGreaterThan(all.list().get(1).id());
        assertThat(all.list().get(0).name()).isEqualTo("进群任务乙");
        assertThat(all.list().get(1).name()).isEqualTo("进群任务甲");

        // keyword 筛
        JoinTaskQuery qKeyword = new JoinTaskQuery();
        qKeyword.setKeyword("甲");
        PageResult<JoinTaskVO> byKeyword = service.listTasks(qKeyword);
        assertThat(byKeyword.total()).isEqualTo(1);
        assertThat(byKeyword.list().get(0).name()).contains("甲");

        // distributionMode 筛
        JoinTaskQuery qMode = new JoinTaskQuery();
        qMode.setDistributionMode("FIXED_ACCOUNT_MULTI_LINK");
        PageResult<JoinTaskVO> byMode = service.listTasks(qMode);
        assertThat(byMode.total()).isEqualTo(1);
        assertThat(byMode.list().get(0).id()).isEqualTo(yi.id());

        // interval 筛
        JoinTaskQuery qInterval = new JoinTaskQuery();
        qInterval.setInterval("10-20s");
        PageResult<JoinTaskVO> byInterval = service.listTasks(qInterval);
        assertThat(byInterval.total()).isEqualTo(1);
        assertThat(byInterval.list().get(0).id()).isEqualTo(jia.id());

        // status 筛
        JoinTaskQuery qDraft = new JoinTaskQuery();
        qDraft.setStatus("DRAFT");
        assertThat(service.listTasks(qDraft).total()).isEqualTo(2);

        JoinTaskQuery qDone = new JoinTaskQuery();
        qDone.setStatus("DONE");
        assertThat(service.listTasks(qDone).total()).isEqualTo(0);

        // pageSize=1 分页
        JoinTaskQuery qPage = new JoinTaskQuery();
        qPage.setPageSize(1);
        PageResult<JoinTaskVO> paged = service.listTasks(qPage);
        assertThat(paged.list()).hasSize(1);
        assertThat(paged.total()).isEqualTo(2);
    }

    // =========================================================================
    // 用例 2 — intervalOptions 去重验证
    // =========================================================================

    /**
     * 建甲(10-20s)、乙(5-15s)再建一个额外的 10-20s 任务 → intervalOptions 含两个标签,
     * 且 "10-20s" 只出现一次(去重)。
     */
    @Test
    void case2_intervalOptions_deduplicated() {
        createJia();   // 10-20s
        createYi();   // 5-15s
        // 额外再建一个 10-20s
        CreateJoinTaskRequest extra = new CreateJoinTaskRequest(
                "额外10-20s任务",
                null, null,
                List.of(new SelectedAccount(1L, "acc1")),
                LINK1,
                "FIXED_ACCOUNTS_PER_LINK",
                1, null, null,
                10, 20, null, null,
                false, 0, "SKIP");
        service.createTask(extra);

        List<String> opts = service.intervalOptions();
        assertThat(opts).contains("10-20s", "5-15s");
        long count = opts.stream().filter("10-20s"::equals).count();
        assertThat(count).isEqualTo(1);
    }

    // =========================================================================
    // 用例 3 — getDetail 配置回填 + NOT_FOUND
    // =========================================================================

    /**
     * getDetail 返回 JSON 快照解析后的 List,配置字段回得对;
     * 不存在 id 抛 BusinessException code=NOT_FOUND。
     */
    @Test
    void case3_getDetail_jsonParseAndNotFound() {
        JoinTaskVO jia = createJia();

        JoinTaskDetailVO detail = service.getDetail(jia.id());

        // JSON 快照解析
        assertThat(detail.accountGroupIds()).containsExactly(10L);
        assertThat(detail.selectedAccountIds()).containsExactlyInAnyOrder(1L, 2L, 3L);

        // 配置字段
        assertThat(detail.distributionMode()).isEqualTo("FIXED_ACCOUNTS_PER_LINK");
        assertThat(detail.accountsPerLink()).isEqualTo(2);
        assertThat(detail.intervalLabel()).isEqualTo("10-20s");
        assertThat(detail.fixedIntervalMinSec()).isEqualTo(10);
        assertThat(detail.fixedIntervalMaxSec()).isEqualTo(20);
        assertThat(detail.status()).isEqualTo("DRAFT");

        // NOT_FOUND
        assertThatThrownBy(() -> service.getDetail(99999999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex ->
                        assertThat(((BusinessException) ex).getCode())
                                .isEqualTo(ErrorCode.NOT_FOUND.code()));
    }

    // =========================================================================
    // 用例 4 — results 行数 + 原始链接直出(系统不脱敏)
    // =========================================================================

    /**
     * results(甲.id) → 4 行(accountsPerLink=2,2链接),按 id 升序;
     * 群链接原样直出(系统不脱敏):每行 link 为 LINK1/LINK2 之一,且两者各至少命中一行。
     */
    @Test
    void case4_results_rowsAndRawLinks() {
        JoinTaskVO jia = createJia();

        List<JoinResultRowVO> rows = service.results(jia.id());

        assertThat(rows).hasSize(4);

        // 链接原样直出,不脱敏:每行 link 恰为原始群链接之一
        assertThat(rows).extracting(JoinResultRowVO::link).containsOnly(LINK1, LINK2);
        assertThat(rows.stream().filter(r -> LINK1.equals(r.link())).count()).isGreaterThanOrEqualTo(1);
        assertThat(rows.stream().filter(r -> LINK2.equals(r.link())).count()).isGreaterThanOrEqualTo(1);
    }
}
