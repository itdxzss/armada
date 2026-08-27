package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.group.model.entity.HistoricalGroupPullExecution;
import com.armada.group.model.entity.HistoricalGroupPullMember;
import com.armada.group.model.enums.HistoricalGroupAddStatus;
import com.armada.group.model.enums.HistoricalGroupContactStatus;
import com.armada.group.model.enums.HistoricalGroupMarketingStatus;
import com.armada.group.model.enums.HistoricalGroupMaterialType;
import com.armada.group.model.enums.HistoricalGroupMemberSendStatus;
import com.armada.group.model.enums.HistoricalGroupPullStatus;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 历史群拉人独立执行域真库测试：锁定租户隔离、幂等约束和原子状态推进。 */
class HistoricalGroupPullPersistenceDbTest extends DbTestBase {

    @Autowired
    private HistoricalGroupPullExecutionMapper executionMapper;

    @Autowired
    private HistoricalGroupPullMemberMapper memberMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v149KeepsExecutionSchemaAndMovesIdempotencyIntoOwnerScope() {
        assertThat(columnNames("historical_group_pull_execution")).containsExactly(
                "id", "tenant_id", "owner_user_id", "created_by", "idempotency_key",
                "unowned_idempotency_key", "operation_account_id", "source_account_group_id",
                "group_jid", "group_subject_snapshot", "invite_link", "puller_account_group_id",
                "puller_account_id", "single_add_count", "marketing_template_id", "normal_count",
                "marketing_count", "invalid_count", "duplicate_count", "pull_success_count",
                "pull_failure_count", "send_success_count", "send_failure_count", "pull_status",
                "marketing_status", "failure_stage", "error_code", "error_message", "started_at",
                "finished_at", "created_at", "updated_at");
        assertThat(columnNames("historical_group_pull_member")).containsExactly(
                "id", "tenant_id", "execution_id", "line_no", "phone", "material_type",
                "account_id", "protocol_account_id_snapshot", "contact_status", "contact_error_code",
                "contact_error_message", "add_status", "add_error_code", "add_error_message",
                "send_status", "send_command_id", "send_result_event_id", "send_error_code",
                "send_error_message", "created_at", "updated_at");

        assertThat(columnComment("historical_group_pull_execution", "pull_status"))
                .isEqualTo("拉人状态:0待执行 1执行中 2成功 3部分成功 4失败");
        assertThat(columnComment("historical_group_pull_execution", "marketing_status"))
                .isEqualTo("营销状态:0不适用 1未开始 2发送中 3成功 4部分成功 5失败");
        assertThat(columnComment("historical_group_pull_member", "material_type"))
                .isEqualTo("料子类型:1普通 2营销");
        assertThat(columnComment("historical_group_pull_member", "contact_status"))
                .isEqualTo("联系人预存状态:0待处理 1成功 2失败");
        assertThat(columnComment("historical_group_pull_member", "add_status"))
                .isEqualTo("拉人状态:0待处理 1成功 2失败");
        assertThat(columnComment("historical_group_pull_member", "send_status"))
                .isEqualTo("成员发送状态:0不适用 1待发送 2发送中 3成功 4失败");

        assertThat(nullableColumns("historical_group_pull_execution")).containsExactly(
                "owner_user_id", "created_by", "unowned_idempotency_key",
                "source_account_group_id", "group_subject_snapshot", "invite_link", "puller_account_id",
                "marketing_template_id", "failure_stage", "error_code", "error_message",
                "started_at", "finished_at");
        assertThat(nullableColumns("historical_group_pull_member")).containsExactly(
                "account_id", "protocol_account_id_snapshot", "contact_error_code",
                "contact_error_message", "add_error_code", "add_error_message", "send_command_id",
                "send_result_event_id", "send_error_code", "send_error_message");

        assertIndex("historical_group_pull_execution", "uq_hgpe_owner_idempotency", true,
                List.of("tenant_id", "owner_user_id", "idempotency_key"));
        assertIndex("historical_group_pull_execution", "uq_hgpe_unowned_idempotency", true,
                List.of("tenant_id", "unowned_idempotency_key"));
        assertIndex("historical_group_pull_execution", "idx_hgpe_owner_time", false,
                List.of("tenant_id", "owner_user_id", "created_at", "id"));
        assertIndex("historical_group_pull_execution", "idx_hgpe_tenant_account_group_time", false,
                List.of("tenant_id", "operation_account_id", "group_jid", "created_at"));
        assertIndex("historical_group_pull_member", "uq_hgpm_tenant_execution_phone", true,
                List.of("tenant_id", "execution_id", "phone"));
        assertIndex("historical_group_pull_member", "uq_hgpm_tenant_send_command", true,
                List.of("tenant_id", "send_command_id"));
        assertIndex("historical_group_pull_member", "uq_hgpm_tenant_send_event", true,
                List.of("tenant_id", "send_result_event_id"));
        assertIndex("historical_group_pull_member", "idx_hgpm_tenant_execution_material", false,
                List.of("tenant_id", "execution_id", "material_type"));
    }

    @Test
    void claimsPendingExecutionOnlyOnceAndKeepsTenantIsolation() {
        long now = System.currentTimeMillis();
        HistoricalGroupPullExecution execution = newExecution("claim-" + now, now);
        assertThat(executionMapper.insert(execution)).isEqualTo(1);

        assertThat(executionMapper.claimStatus(
                execution.getId(), HistoricalGroupPullStatus.PENDING.code(),
                HistoricalGroupPullStatus.RUNNING.code(), now + 1)).isEqualTo(1);
        assertThat(executionMapper.claimStatus(
                execution.getId(), HistoricalGroupPullStatus.PENDING.code(),
                HistoricalGroupPullStatus.RUNNING.code(), now + 2)).isZero();
        assertThat(executionMapper.selectByTenantAndId(TEST_TENANT_ID, execution.getId()).getPullStatus())
                .isEqualTo(HistoricalGroupPullStatus.RUNNING.code());

        long foreignId = now + 9_000_000_000L;
        jdbc.update("""
                INSERT INTO historical_group_pull_execution (
                  id, tenant_id, idempotency_key, operation_account_id, group_jid,
                  puller_account_group_id, single_add_count, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, foreignId, TEST_TENANT_ID + 1, "foreign-" + now, 91L,
                "120363foreign@g.us", 92L, 5, now, now);
        assertThat(executionMapper.selectByTenantAndId(TEST_TENANT_ID + 1, foreignId)).isNull();
    }

    @Test
    void rejectsDuplicateExecutionIdempotencyKey() {
        long now = System.currentTimeMillis();
        HistoricalGroupPullExecution first = newExecution("duplicate-" + now, now);
        HistoricalGroupPullExecution duplicate = newExecution(first.getIdempotencyKey(), now + 1);
        assertThat(executionMapper.insert(first)).isEqualTo(1);
        assertThatThrownBy(() -> executionMapper.insert(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ordersMembersAndFreezesMemberAndCommandResults() {
        long now = System.currentTimeMillis();
        HistoricalGroupPullExecution execution = newExecution("members-" + now, now);
        executionMapper.insert(execution);
        HistoricalGroupPullMember later = newMember(execution.getId(), 20, "8613800000020", now);
        HistoricalGroupPullMember earlier = newMember(execution.getId(), 10, "8613800000010", now);
        later.setSendStatus(HistoricalGroupMemberSendStatus.PENDING.code());
        earlier.setMaterialType(HistoricalGroupMaterialType.NORMAL.code());
        earlier.setSendStatus(HistoricalGroupMemberSendStatus.NOT_APPLICABLE.code());
        assertThat(memberMapper.batchInsert(List.of(later, earlier))).isEqualTo(2);

        List<HistoricalGroupPullMember> ordered = memberMapper.selectOrderedByExecutionId(execution.getId());
        assertThat(ordered).extracting(HistoricalGroupPullMember::getLineNo).containsExactly(20, 10);
        HistoricalGroupPullMember target = ordered.get(0);
        // 联系人保存失败是警告；只要 ADD 成功，该号码仍计入拉人成功而不是失败。
        assertThat(memberMapper.updateContactResultIfPending(
                target.getId(), HistoricalGroupContactStatus.PENDING.code(),
                HistoricalGroupContactStatus.FAILED.code(), "CONTACT_SAVE_FAILED", "save failed", now + 1))
                .isEqualTo(1);
        assertThat(memberMapper.updateContactResultIfPending(
                target.getId(), HistoricalGroupContactStatus.PENDING.code(),
                HistoricalGroupContactStatus.FAILED.code(), "late", "late", now + 2)).isZero();
        assertThat(memberMapper.updateAddResultIfPending(
                target.getId(), HistoricalGroupAddStatus.PENDING.code(),
                HistoricalGroupAddStatus.SUCCESS.code(), null, null, now + 3)).isEqualTo(1);
        assertThat(memberMapper.markSendSendingIfPending(
                target.getId(), HistoricalGroupMemberSendStatus.PENDING.code(),
                HistoricalGroupMemberSendStatus.SENDING.code(), "command-" + now, now + 4)).isEqualTo(1);
        assertThat(memberMapper.markSendSendingIfPending(
                target.getId(), HistoricalGroupMemberSendStatus.PENDING.code(),
                HistoricalGroupMemberSendStatus.SENDING.code(), "command-late-" + now, now + 5)).isZero();
        assertThat(memberMapper.updateSendResultByCommandId(
                "command-" + now, "event-" + now, HistoricalGroupMemberSendStatus.SENDING.code(),
                HistoricalGroupMemberSendStatus.SUCCESS.code(), null, null, now + 6)).isEqualTo(1);
        assertThat(memberMapper.updateSendResultByCommandId(
                "command-" + now, "event-late-" + now, HistoricalGroupMemberSendStatus.SENDING.code(),
                HistoricalGroupMemberSendStatus.FAILED.code(), "late", "late", now + 7)).isZero();

        HistoricalGroupPullMember persisted = memberMapper.selectByTenantAndId(TEST_TENANT_ID, target.getId());
        assertThat(persisted.getContactStatus()).isEqualTo(HistoricalGroupContactStatus.FAILED.code());
        assertThat(persisted.getAddStatus()).isEqualTo(HistoricalGroupAddStatus.SUCCESS.code());
        assertThat(persisted.getSendStatus()).isEqualTo(HistoricalGroupMemberSendStatus.SUCCESS.code());
        assertThat(persisted.getSendResultEventId()).isEqualTo("event-" + now);

        assertThat(executionMapper.refreshTerminalStats(
                execution.getId(), HistoricalGroupAddStatus.SUCCESS.code(), HistoricalGroupMemberSendStatus.SUCCESS.code(),
                HistoricalGroupMemberSendStatus.FAILED.code(), now + 8)).isEqualTo(1);
        HistoricalGroupPullExecution refreshed =
                executionMapper.selectByTenantAndId(TEST_TENANT_ID, execution.getId());
        assertThat(refreshed.getPullSuccessCount()).isEqualTo(1);
        assertThat(refreshed.getPullFailureCount()).isEqualTo(1);
        assertThat(refreshed.getSendSuccessCount()).isEqualTo(1);
        assertThat(refreshed.getSendFailureCount()).isZero();
    }

    @Test
    void rejectsDuplicatePhoneAndPreservesLongRawErrors() {
        long now = System.currentTimeMillis();
        String longError = "原始协议错误".repeat(1_000);
        HistoricalGroupPullExecution execution = newExecution("errors-" + now, now);
        execution.setErrorMessage(longError);
        executionMapper.insert(execution);
        assertThat(executionMapper.selectByTenantAndId(TEST_TENANT_ID, execution.getId()).getErrorMessage())
                .isEqualTo(longError);

        HistoricalGroupPullMember first = newMember(execution.getId(), 1, "8613900000001", now);
        first.setContactErrorMessage(longError);
        first.setAddErrorMessage(longError);
        first.setSendErrorMessage(longError);
        assertThat(memberMapper.batchInsert(List.of(first))).isEqualTo(1);
        HistoricalGroupPullMember persisted = memberMapper.selectOrderedByExecutionId(execution.getId()).get(0);
        assertThat(persisted.getContactErrorMessage()).isEqualTo(longError);
        assertThat(persisted.getAddErrorMessage()).isEqualTo(longError);
        assertThat(persisted.getSendErrorMessage()).isEqualTo(longError);

        HistoricalGroupPullMember duplicate = newMember(execution.getId(), 2, first.getPhone(), now + 1);
        assertThatThrownBy(() -> memberMapper.batchInsert(List.of(duplicate)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private HistoricalGroupPullExecution newExecution(String idempotencyKey, long now) {
        HistoricalGroupPullExecution row = new HistoricalGroupPullExecution();
        row.setOwnerUserId(1001L);
        row.setCreatedBy(1001L);
        row.setIdempotencyKey(idempotencyKey);
        row.setOperationAccountId(1001L);
        row.setGroupJid("120363test@g.us");
        row.setPullerAccountGroupId(2001L);
        row.setSingleAddCount(5);
        row.setPullStatus(HistoricalGroupPullStatus.PENDING.code());
        row.setMarketingStatus(HistoricalGroupMarketingStatus.NOT_APPLICABLE.code());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private HistoricalGroupPullMember newMember(long executionId, int lineNo, String phone, long now) {
        HistoricalGroupPullMember row = new HistoricalGroupPullMember();
        row.setExecutionId(executionId);
        row.setLineNo(lineNo);
        row.setPhone(phone);
        row.setMaterialType(HistoricalGroupMaterialType.MARKETING.code());
        row.setContactStatus(HistoricalGroupContactStatus.PENDING.code());
        row.setAddStatus(HistoricalGroupAddStatus.PENDING.code());
        row.setSendStatus(HistoricalGroupMemberSendStatus.NOT_APPLICABLE.code());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private List<String> columnNames(String tableName) {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ?
                ORDER BY ordinal_position
                """, String.class, tableName);
    }

    private List<String> nullableColumns(String tableName) {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND is_nullable = 'YES'
                ORDER BY ordinal_position
                """, String.class, tableName);
    }

    private String columnComment(String tableName, String columnName) {
        return jdbc.queryForObject("""
                SELECT column_comment FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, String.class, tableName, columnName);
    }

    private void assertIndex(String tableName, String indexName, boolean unique, List<String> columns) {
        List<String> actualColumns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                ORDER BY seq_in_index
                """, String.class, tableName, indexName);
        Integer nonUnique = jdbc.queryForObject("""
                SELECT MIN(non_unique) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                """, Integer.class, tableName, indexName);
        assertThat(actualColumns).isEqualTo(columns);
        assertThat(nonUnique).isEqualTo(unique ? 0 : 1);
    }
}
