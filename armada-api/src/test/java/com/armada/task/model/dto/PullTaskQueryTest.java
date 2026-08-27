package com.armada.task.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.task.model.enums.PullTaskGroupSource;
import com.armada.task.model.enums.PullTaskType;
import org.junit.jupiter.api.Test;

/** 拉群任务列表查询参数归一化测试。 */
class PullTaskQueryTest {

    @Test
    void trimsTextAndKeepsTypedFilters() {
        PullTaskQuery query = new PullTaskQuery();
        query.setId(8L);
        query.setKeyword("  印度  ");
        query.setStatus("  EXECUTING ");
        query.setTaskType(PullTaskType.GROUP_MARKETING);
        query.setGroupSource(PullTaskGroupSource.HISTORICAL);
        query.setOperator("  运营甲  ");

        assertThat(query.toFilter()).isEqualTo(new PullTaskFilter(
                8L, "印度", "EXECUTING", PullTaskType.GROUP_MARKETING,
                PullTaskGroupSource.HISTORICAL, "运营甲", null));
    }

    @Test
    void convertsBlankTextToNullAndUsesBoundedPagination() {
        PullTaskQuery query = new PullTaskQuery();
        query.setKeyword("  ");
        query.setStatus("");
        query.setOperator(null);
        query.setPage(0);
        query.setPageSize(20_000);

        assertThat(query.toFilter()).isEqualTo(
                new PullTaskFilter(null, null, null, null, null, null, null));
        assertThat(query.getPage()).isEqualTo(1);
        assertThat(query.getPageSize()).isEqualTo(1_000);
        assertThat(query.getOffset()).isZero();
    }
}
