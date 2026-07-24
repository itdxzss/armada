package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 人工账号迁移与分组营销锁并发保护 SQL 测试。 */
class AccountGroupMarketingLockGuardTest {

    @Test
    void migrationLocksAccountsAndGroupsAndChecksActiveBuilderReference() throws IOException {
        String accountXml = resource("/mapper/account/AccountMapper.xml");
        String groupXml = resource("/mapper/account/AccountGroupMapper.xml");

        assertThat(selectBlock(accountXml, "selectActiveByIdsForUpdate"))
                .contains("ORDER BY id")
                .contains("FOR UPDATE");
        assertThat(selectBlock(groupXml, "selectByIdsForUpdate"))
                .contains("ORDER BY id")
                .contains("FOR UPDATE");
        assertThat(selectBlock(groupXml, "countActiveBuilderGroupReferences"))
                .contains("FROM group_pull_marketing_task")
                .contains("builder_group_id IN")
                .contains("resource_status IN (2, 3)");
    }

    private String resource(String path) throws IOException {
        return new String(getClass().getResourceAsStream(path).readAllBytes(), StandardCharsets.UTF_8);
    }

    private String selectBlock(String xml, String id) {
        String open = "<select id=\"" + id + "\"";
        int start = xml.indexOf(open);
        assertThat(start).as("select %s exists", id).isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</select>", start);
        assertThat(end).as("select %s closes", id).isGreaterThan(start);
        return xml.substring(start, end);
    }
}
