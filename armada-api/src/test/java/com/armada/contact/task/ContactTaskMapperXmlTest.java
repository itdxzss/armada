package com.armada.contact.task;

import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
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

/** 通讯录任务 Mapper XML 静态契约测试。本机无库，只校验契约。 */
class ContactTaskMapperXmlTest {

    private static String xml(String name) throws IOException {
        return Files.readString(
                Path.of("src/main/resources/mapper/contact/" + name), StandardCharsets.UTF_8);
    }

    private static Set<String> declaredMethods(Class<?> mapper) {
        return Arrays.stream(mapper.getDeclaredMethods())
                .filter(m -> !m.isDefault())
                .map(Method::getName)
                .collect(Collectors.toSet());
    }

    @Test
    void taskMapperXmlDeclaresEveryInterfaceMethod() throws IOException {
        String sql = xml("ContactFriendTaskMapper.xml");

        assertThat(sql).contains(
                "namespace=\"com.armada.contact.task.mapper.ContactFriendTaskMapper\"");
        for (String method : declaredMethods(ContactFriendTaskMapper.class)) {
            assertThat(sql).as("XML 缺少语句 id=%s", method).contains("id=\"" + method + "\"");
        }
    }

    @Test
    void accountMapperXmlDeclaresEveryInterfaceMethod() throws IOException {
        String sql = xml("ContactFriendTaskAccountMapper.xml");

        assertThat(sql).contains(
                "namespace=\"com.armada.contact.task.mapper.ContactFriendTaskAccountMapper\"");
        for (String method : declaredMethods(ContactFriendTaskAccountMapper.class)) {
            assertThat(sql).as("XML 缺少语句 id=%s", method).contains("id=\"" + method + "\"");
        }
    }

    @Test
    void listQueryExcludesSoftDeletedTasks() throws IOException {
        assertThat(xml("ContactFriendTaskMapper.xml")).contains("deleted_at IS NULL");
    }

    @Test
    void runStatusUpdateIsGuardedByExpectedStatus() throws IOException {
        // 状态迁移必须条件更新，防止并发下两个动作互相覆盖
        assertThat(xml("ContactFriendTaskMapper.xml"))
                .contains("run_status = #{expectedRunStatus}");
    }

    @Test
    void accountSortColumnIsWhitelistedNotInterpolatedRaw() throws IOException {
        String sql = xml("ContactFriendTaskAccountMapper.xml");

        // 排序列必须走 choose 白名单，不能把用户输入直接拼进 ORDER BY
        assertThat(sql).contains("<choose>");
        assertThat(sql).doesNotContain("ORDER BY ${");
    }

    @Test
    void recipientMapperXmlDeclaresEveryInterfaceMethod() throws IOException {
        String sql = xml("ContactFriendTaskRecipientMapper.xml");

        assertThat(sql).contains(
                "namespace=\"com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper\"");
        for (String method : declaredMethods(ContactFriendTaskRecipientMapper.class)) {
            assertThat(sql).as("XML 缺少语句 id=%s", method).contains("id=\"" + method + "\"");
        }
    }

    @Test
    void recipientClaimIsGuardedByPendingStatus() throws IOException {
        // 抢批必须条件更新，否则两个轮次会把同一条收件人投两次
        assertThat(xml("ContactFriendTaskRecipientMapper.xml"))
                .contains("send_status = 'PENDING'");
    }

    @Test
    void recipientResultWriteBackIsGuardedBySendingStatus() throws IOException {
        // 回执重复到达时条件更新返回 0，调用方据此跳过计数，保证幂等
        assertThat(xml("ContactFriendTaskRecipientMapper.xml"))
                .contains("send_status = 'SENDING'");
    }

    @Test
    void recipientBatchInsertIgnoresIdempotencyKeyConflict() throws IOException {
        // 幂等键 (task_id, task_account_id, contact_phone) 冲突时忽略，重复展开不产生重复收件人
        assertThat(xml("ContactFriendTaskRecipientMapper.xml")).contains("INSERT IGNORE");
    }
}
