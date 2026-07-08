package com.armada.marketing.mapper;

import com.armada.marketing.model.enums.GroupCreationMarketingItemStatus;
import com.armada.marketing.model.enums.GroupCreationMarketingTaskStatus;
import com.armada.marketing.model.support.GroupCreationMarketingNoAvailableAccountUpdate;
import com.armada.marketing.model.support.GroupCreationMarketingRetryResetUpdate;
import com.armada.testsupport.DbTestBase;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import static org.assertj.core.api.Assertions.assertThat;

class GroupCreationMarketingTaskMapperDbTest extends DbTestBase {

    @Autowired
    private GroupCreationMarketingTaskMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void abandonedAndFailedItemsUpdateParentCountsAndFinalStatus() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("mapper-counts-" + now, 2, now);
        Long firstItemId = insertItem(taskId, 0, GroupCreationMarketingItemStatus.PENDING.code(), null, now);
        Long secondItemId = insertItem(taskId, 1, GroupCreationMarketingItemStatus.PENDING.code(), null, now);

        assertThat(mapper.claimItem(firstItemId, GroupCreationMarketingItemStatus.PENDING.code(),
                GroupCreationMarketingItemStatus.GROUP_CREATING.code(), now + 1)).isPositive();
        assertThat(taskStatus(taskId)).isEqualTo(GroupCreationMarketingTaskStatus.RUNNING.code());

        assertThat(mapper.markItemAbandoned(firstItemId, "ACCOUNT_OFFLINE", "账号离线", now + 2)).isPositive();
        assertThat(taskColumn(taskId, "abandoned_count")).isEqualTo(1);
        assertThat(taskStatus(taskId)).isEqualTo(GroupCreationMarketingTaskStatus.RUNNING.code());

        assertThat(mapper.claimItem(secondItemId, GroupCreationMarketingItemStatus.PENDING.code(),
                GroupCreationMarketingItemStatus.GROUP_CREATING.code(), now + 3)).isPositive();
        assertThat(mapper.markItemFailed(secondItemId, "GROUP_CREATE_FAILED", "建群失败", null, now + 4)).isPositive();

        assertThat(taskColumn(taskId, "failed_count")).isEqualTo(1);
        assertThat(taskColumn(taskId, "abandoned_count")).isEqualTo(1);
        assertThat(taskStatus(taskId)).isEqualTo(GroupCreationMarketingTaskStatus.PARTIAL_FAILED.code());
        assertThat(taskFinishedAt(taskId)).isEqualTo(now + 4);
    }

    @Test
    void marketingResultSuccessUpdatesParentCountsAndFinalStatus() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("mapper-success-" + now, 1, now);
        long attemptId = now % 1_000_000L + 1000L;
        Long itemId = insertItem(taskId, 0, GroupCreationMarketingItemStatus.MARKETING_SENDING.code(), attemptId, now);

        assertThat(mapper.markItemSuccessByMarketingAttemptId(attemptId, now + 1)).isPositive();

        assertThat(itemStatus(itemId)).isEqualTo(GroupCreationMarketingItemStatus.SUCCESS.code());
        assertThat(taskColumn(taskId, "success_count")).isEqualTo(1);
        assertThat(taskStatus(taskId)).isEqualTo(GroupCreationMarketingTaskStatus.SUCCESS.code());
        assertThat(taskFinishedAt(taskId)).isEqualTo(now + 1);
    }

    @Test
    void stopMarksOpenItemsAbandonedAndParentStopped() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("mapper-stop-" + now, 4, now);
        Long pendingItemId = insertItem(taskId, 0, GroupCreationMarketingItemStatus.PENDING.code(), null, now);
        Long creatingItemId = insertItem(taskId, 1, GroupCreationMarketingItemStatus.GROUP_CREATING.code(), null, now);
        Long sendingItemId = insertItem(taskId, 2, GroupCreationMarketingItemStatus.MARKETING_SENDING.code(), now + 10, now);
        Long successItemId = insertItem(taskId, 3, GroupCreationMarketingItemStatus.SUCCESS.code(), now + 11, now);

        int stoppableCount = mapper.countStoppableItems(taskId);
        assertThat(stoppableCount).isEqualTo(3);

        assertThat(mapper.stopStoppableItems(taskId, "TASK_STOPPED", "任务已停止", now + 1)).isEqualTo(3);
        assertThat(mapper.stopTask(taskId, GroupCreationMarketingTaskStatus.STOPPED.code(), stoppableCount, now + 1))
                .isEqualTo(1);

        assertThat(itemStatus(pendingItemId)).isEqualTo(GroupCreationMarketingItemStatus.ABANDONED.code());
        assertThat(itemStatus(creatingItemId)).isEqualTo(GroupCreationMarketingItemStatus.ABANDONED.code());
        assertThat(itemStatus(sendingItemId)).isEqualTo(GroupCreationMarketingItemStatus.ABANDONED.code());
        assertThat(itemStatus(successItemId)).isEqualTo(GroupCreationMarketingItemStatus.SUCCESS.code());
        assertThat(taskColumn(taskId, "abandoned_count")).isEqualTo(3);
        assertThat(taskStatus(taskId)).isEqualTo(GroupCreationMarketingTaskStatus.STOPPED.code());
        assertThat(taskFinishedAt(taskId)).isEqualTo(now + 1);
    }

    @Test
    void accountRetryResetsOnlyOneItemWithoutParentCounterChanges() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("mapper-retry-" + now, 1, now);
        Long itemId = insertItem(taskId, 0, GroupCreationMarketingItemStatus.MARKETING_SENDING.code(), null, now);
        jdbc.update("UPDATE group_creation_marketing_item SET command_id = ? WHERE id = ?", "cmd_1", itemId);

        GroupCreationMarketingRetryResetUpdate update = new GroupCreationMarketingRetryResetUpdate();
        update.setId(itemId);
        update.setAccountId(9L);
        update.setAccountPhone("8613999999999");
        update.setProtocolAccountId("acc_9");
        update.setFromStatus(GroupCreationMarketingItemStatus.MARKETING_SENDING.code());
        update.setExpectedCommandId("cmd_1");
        update.setPendingStatus(GroupCreationMarketingItemStatus.PENDING.code());
        update.setNextRunAt(now + 1);
        update.setRetryHistoryJson("{\"entries\":[]}");
        update.setUpdatedAt(now + 1);

        assertThat(mapper.resetItemForAccountRetry(update)).isEqualTo(1);

        assertThat(itemStatus(itemId)).isEqualTo(GroupCreationMarketingItemStatus.PENDING.code());
        assertThat(itemLongColumn(itemId, "account_id")).isEqualTo(9L);
        assertThat(itemStringColumn(itemId, "protocol_account_id")).isEqualTo("acc_9");
        assertThat(itemStringColumn(itemId, "command_id")).isNull();
        assertThat(itemStringColumn(itemId, "retry_history_json")).isEqualTo("{\"entries\": []}");
        assertThat(taskColumn(taskId, "success_count")).isZero();
        assertThat(taskColumn(taskId, "failed_count")).isZero();
        assertThat(taskColumn(taskId, "abandoned_count")).isZero();
    }

    @Test
    void noAvailableAccountMarksOnlyOneItemAbandonedAndStoresRetryHistory() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("mapper-no-account-" + now, 1, now);
        Long itemId = insertItem(taskId, 0, GroupCreationMarketingItemStatus.GROUP_CREATING.code(), null, now);

        GroupCreationMarketingNoAvailableAccountUpdate update = new GroupCreationMarketingNoAvailableAccountUpdate();
        update.setId(itemId);
        update.setReasonCode("NO_AVAILABLE_ACCOUNT");
        update.setReasonMessage("没有可用账号");
        update.setFromStatus(GroupCreationMarketingItemStatus.GROUP_CREATING.code());
        update.setExpectedCommandId(null);
        update.setRetryHistoryJson("{\"entries\":[]}");
        update.setFinishedAt(now + 1);

        assertThat(mapper.markItemNoAvailableAccount(update)).isEqualTo(1);

        assertThat(itemStatus(itemId)).isEqualTo(GroupCreationMarketingItemStatus.ABANDONED.code());
        assertThat(itemStringColumn(itemId, "reason_code")).isEqualTo("NO_AVAILABLE_ACCOUNT");
        assertThat(itemStringColumn(itemId, "reason_message")).isEqualTo("没有可用账号");
        assertThat(itemStringColumn(itemId, "retry_history_json")).isEqualTo("{\"entries\": []}");
        assertThat(taskColumn(taskId, "abandoned_count")).isEqualTo(1);
        assertThat(taskFinishedAt(taskId)).isEqualTo(now + 1);
    }

    @Test
    void itemMemberSnapshotColumnsAreMapped() {
        long now = System.currentTimeMillis();
        Long taskId = insertTask("mapper-export-snapshot-" + now, 1, now);
        Long itemId = insertItem(taskId, 0, GroupCreationMarketingItemStatus.MARKETING_SENDING.code(), null, now);

        jdbc.update("""
                UPDATE group_creation_marketing_item
                SET send_member_count = ?,
                    send_member_count_checked_at = ?
                WHERE id = ?
                """, 6, now + 2, itemId);

        var item = mapper.selectItemById(itemId);

        assertThat(item.getSendMemberCount()).isEqualTo(6);
        assertThat(item.getSendMemberCountCheckedAt()).isEqualTo(now + 2);
    }

    private Long insertTask(String name, int matchedItemCount, long now) {
        return insertReturningId("""
                INSERT INTO group_creation_marketing_task
                    (tenant_id, task_name, account_group_id, account_group_name,
                     marketing_template_id, marketing_template_name, status,
                     matched_item_count, unmatched_file_count, success_count,
                     failed_count, abandoned_count, created_at, updated_at)
                VALUES (?, ?, 1, 'A组', 1, '模板', 1, ?, 0, 0, 0, 0, ?, ?)
                """, TEST_TENANT_ID, name, matchedItemCount, now, now);
    }

    private Long insertItem(Long taskId, int fileIndex, int status, Long attemptId, long now) {
        return insertReturningId("""
                INSERT INTO group_creation_marketing_item
                    (tenant_id, task_id, file_index, file_name, material_content,
                     participant_count, account_id, account_phone, protocol_account_id,
                     group_subject, marketing_attempt_id, status, next_run_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, '8613900000000', 1, 1, '8613000000000',
                        'acc_1', '活动群', ?, ?, ?, ?, ?)
                """, TEST_TENANT_ID, taskId, fileIndex, "file-" + fileIndex + ".txt",
                attemptId, status, now, now, now);
    }

    private int taskStatus(Long taskId) {
        return taskColumn(taskId, "status");
    }

    private int itemStatus(Long itemId) {
        Integer value = jdbc.queryForObject(
                "SELECT status FROM group_creation_marketing_item WHERE id = ?",
                Integer.class,
                itemId);
        return value == null ? -1 : value;
    }

    private Long itemLongColumn(Long itemId, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM group_creation_marketing_item WHERE id = ?",
                Long.class,
                itemId);
    }

    private String itemStringColumn(Long itemId, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM group_creation_marketing_item WHERE id = ?",
                String.class,
                itemId);
    }

    private int taskColumn(Long taskId, String column) {
        Integer value = jdbc.queryForObject(
                "SELECT " + column + " FROM group_creation_marketing_task WHERE id = ?",
                Integer.class,
                taskId);
        return value == null ? -1 : value;
    }

    private Long taskFinishedAt(Long taskId) {
        return jdbc.queryForObject(
                "SELECT finished_at FROM group_creation_marketing_task WHERE id = ?",
                Long.class,
                taskId);
    }

    private Long insertReturningId(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
