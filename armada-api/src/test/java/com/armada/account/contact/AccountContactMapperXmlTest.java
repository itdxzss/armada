package com.armada.account.contact;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.mapper.AccountContactSyncMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 通讯录 Mapper XML 与接口的静态契约测试。本机无库，只校验契约不校验行为。 */
class AccountContactMapperXmlTest {

    private static String xml(String name) throws IOException {
        return Files.readString(
                Path.of("src/main/resources/mapper/account/" + name), StandardCharsets.UTF_8);
    }

    private static Set<String> declaredMethods(Class<?> mapper) {
        return Arrays.stream(mapper.getDeclaredMethods())
                .filter(m -> !m.isDefault())
                .map(Method::getName)
                .collect(Collectors.toSet());
    }

    @Test
    void contactMapperXmlDeclaresEveryInterfaceMethod() throws IOException {
        String sql = xml("AccountContactMapper.xml");

        assertThat(sql).contains(
                "namespace=\"com.armada.account.contact.mapper.AccountContactMapper\"");
        for (String method : declaredMethods(AccountContactMapper.class)) {
            assertThat(sql).as("XML 缺少语句 id=%s", method).contains("id=\"" + method + "\"");
        }
    }

    @Test
    void syncMapperXmlDeclaresEveryInterfaceMethod() throws IOException {
        String sql = xml("AccountContactSyncMapper.xml");

        assertThat(sql).contains(
                "namespace=\"com.armada.account.contact.mapper.AccountContactSyncMapper\"");
        for (String method : declaredMethods(AccountContactSyncMapper.class)) {
            assertThat(sql).as("XML 缺少语句 id=%s", method).contains("id=\"" + method + "\"");
        }
    }

    @Test
    void batchWriteIsUpsertNotTruncateThenInsert() throws IOException {
        String sql = xml("AccountContactMapper.xml");

        // 整批替换靠 upsert + 扫尾删除实现；不能先全量清空再插，
        // 否则同步中途失败会把账号通讯录清空。
        assertThat(sql).contains("ON DUPLICATE KEY UPDATE");
        assertThat(sql).doesNotContain("TRUNCATE");
    }

    @Test
    void staleSweepIsBoundedByAccountAndSyncedAt() throws IOException {
        String sql = xml("AccountContactMapper.xml");

        assertThat(sql)
                .contains("account_id = #{accountId}")
                .contains("synced_at &lt; #{syncedAt}");
    }

    @Test
    void accountStateContactCountUpdateExists() throws IOException {
        String sql = xml("AccountStateMapper.xml");

        assertThat(sql)
                .contains("id=\"updateContactCounts\"")
                .contains("contact_named_num = #{namedNum}")
                .contains("contact_mutual_num = #{mutualNum}");
    }

    @Test
    void namedContactQueryIsBoundedAndFiltersByNamedFlag() throws IOException {
        // 发送目标集口径是「通讯录里有名字」（设计 §2.8），不是双向好友
        String sql = xml("AccountContactMapper.xml");

        assertThat(sql)
                .contains("id=\"selectNamedByAccount\"")
                .contains("is_named = 1")
                .contains("LIMIT #{limit}");
    }
}
