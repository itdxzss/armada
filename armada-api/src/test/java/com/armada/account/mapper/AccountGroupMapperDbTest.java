package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.dto.AccountGroupQuery;
import com.armada.account.model.entity.AccountGroup;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * AccountGroupMapper 真库测试:验 insert/软删/复活/countAccountsByGroupId 流程。
 * 每个 @Test 在 @Transactional 内运行并回滚,数据互不干扰。
 */
class AccountGroupMapperDbTest extends DbTestBase {

    @Autowired
    AccountGroupMapper mapper;

    private AccountGroup build(String name) {
        AccountGroup g = new AccountGroup();
        g.setName(name);
        g.setRemark("r");
        g.setSystemBuiltin(0);
        long now = 1_700_000_000_000L;          // 固定 epoch,测试不依赖 System.currentTimeMillis
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        return g;
    }

    @Test
    void insert_then_selectActiveByName() {
        AccountGroup g = build("分组A");
        mapper.insert(g);
        assertThat(g.getId()).isNotNull();
        assertThat(mapper.selectActiveByName("分组A")).isNotNull();
        assertThat(mapper.selectDeletedByName("分组A")).isNull();
    }

    @Test
    void softDelete_then_reviveByName() {
        AccountGroup g = build("分组B");
        mapper.insert(g);
        mapper.softDeleteByIds(List.of(g.getId()), 1_700_000_000_001L);
        assertThat(mapper.selectActiveByName("分组B")).isNull();
        assertThat(mapper.selectDeletedByName("分组B")).isNotNull();
        mapper.reviveById(g.getId());
        assertThat(mapper.selectActiveByName("分组B")).isNotNull();
    }

    @Test
    void countAccountsByGroupId_zero_whenEmpty() {
        AccountGroup g = build("空组");
        mapper.insert(g);
        assertThat(mapper.countAccountsByGroupId(g.getId())).isEqualTo(0L);
    }
}
