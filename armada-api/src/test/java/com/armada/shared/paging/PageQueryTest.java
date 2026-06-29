package com.armada.shared.paging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * PageQuery 分页参数钳制单测。
 */
class PageQueryTest {

    @Test
    void setPageSize_capsAtOneThousand() {
        PageQuery query = new PageQuery();

        query.setPageSize(1200);

        assertThat(query.getPageSize()).isEqualTo(1000);
    }
}
