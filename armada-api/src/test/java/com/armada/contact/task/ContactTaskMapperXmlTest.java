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

    @Test
    void dueTaskScanIsBoundedAndSkipsSoftDeleted() throws IOException {
        String sql = xml("ContactFriendTaskMapper.xml");

        assertThat(sql).contains("id=\"selectDueRunningTasks\"");
        assertThat(sql).contains("id=\"selectDueScheduledTasks\"");
        assertThat(sql).contains("LIMIT #{limit}");
        assertThat(sql).contains("deleted_at IS NULL");
    }

    @Test
    void roundClaimIsTheConcurrencyGate() throws IOException {
        // claimDueRound 是并发闸门：只有一个线程能把到期任务推进到下一轮
        String sql = xml("ContactFriendTaskMapper.xml");

        assertThat(sql).contains("id=\"claimDueRound\"");
        assertThat(sql).contains("current_round_no = current_round_no + 1");
        assertThat(sql).contains("next_round_at &lt;= #{now}");
    }

    @Test
    void scheduledStartOnlyPromotesEnabledNotStartedTasks() throws IOException {
        String sql = xml("ContactFriendTaskMapper.xml");

        assertThat(sql).contains("id=\"startDueScheduledTask\"");
        assertThat(sql).contains("is_enabled = 1");
    }

    @Test
    void taskColumnsExposeCurrentRoundNo() throws IOException {
        // 轮次号要被 worker 读到才能算下一轮，漏在列清单外会永远读成 null
        assertThat(xml("ContactFriendTaskMapper.xml")).contains("current_round_no");
    }

    @Test
    void completionDerivesAveragesFromAccountRows() throws IOException {
        // 号均发量与封号数都从账号读模型推导，不靠调用方传值，避免口径分裂
        String sql = xml("ContactFriendTaskMapper.xml");

        assertThat(sql).contains("id=\"completeDrainedTask\"");
        assertThat(sql).contains("avg_send_per_account");
        assertThat(sql).contains("invalid_account_num");
        assertThat(sql).contains("NULLIF(");
    }

    @Test
    void accountInsertBackfillsGeneratedKey() throws IOException {
        // 展开收件人需要 task_account.id，插入必须回填主键
        String sql = xml("ContactFriendTaskAccountMapper.xml");

        assertThat(sql).contains("useGeneratedKeys=\"true\"");
        assertThat(sql).contains("keyProperty=\"id\"");
    }

    @Test
    void drainedAccountSettlementDistinguishesDoneFromFailed() throws IOException {
        // 一条都没发成功的账号收敛为 FAILED，用作 invalid_account_num 的口径
        String sql = xml("ContactFriendTaskAccountMapper.xml");

        assertThat(sql).contains("id=\"settleDrainedAccounts\"");
        assertThat(sql).contains("'FAILED'");
        assertThat(sql).contains("'DONE'");
    }
}
