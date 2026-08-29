package com.armada.account.selection;

import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
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

/** 账号圈选 Mapper XML 静态契约测试。本机无库，只校验契约。 */
class AccountFilterSelectionMapperXmlTest {

    private static String xml() throws IOException {
        return Files.readString(
                Path.of("src/main/resources/mapper/account/AccountFilterSelectionMapper.xml"),
                StandardCharsets.UTF_8);
    }

    private static Set<String> declaredMethods(Class<?> mapper) {
        return Arrays.stream(mapper.getDeclaredMethods())
                .filter(m -> !m.isDefault())
                .map(Method::getName)
                .collect(Collectors.toSet());
    }

    @Test
    void declaresEveryInterfaceMethod() throws IOException {
        String sql = xml();

        assertThat(sql).contains(
                "namespace=\"com.armada.account.selection.mapper.AccountFilterSelectionMapper\"");
        for (String method : declaredMethods(AccountFilterSelectionMapper.class)) {
            assertThat(sql).as("XML 缺少语句 id=%s", method).contains("id=\"" + method + "\"");
        }
    }

    @Test
    void excludesSoftDeletedAccounts() throws IOException {
        assertThat(xml()).contains("a.deleted_at IS NULL");
    }

    @Test
    void enforcesNormalAndNotExportedAccountState() throws IOException {
        String sql = xml();

        assertThat(sql).contains("s.account_state = #{normalAccountState}");
        assertThat(sql).contains("s.account_state &lt;&gt; #{exportedAccountState}");
    }

    @Test
    void requiresProtocolFactsForSendableAccounts() throws IOException {
        // 没有协议句柄的号发不出消息，圈号阶段就要排掉，别让它进 task_account 占坑
        assertThat(xml()).contains("a.protocol_account_id IS NOT NULL");
    }

    @Test
    void boundsResultSetWithLimit() throws IOException {
        assertThat(xml()).contains("LIMIT #{limit}");
    }

    @Test
    void pushesFriendCountBoundsToContactMutualColumn() throws IOException {
        // 筛选控件叫「双向好友数」，对应 account_state.contact_mutual_num（设计 §2.8）
        assertThat(xml()).contains("contact_mutual_num");
    }
}
