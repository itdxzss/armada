package com.armada.marketing.service;

import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.testsupport.DbTestBase;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 营销模板删除对关联营销任务的联动规则。
 */
class MarketingTemplateDeletionDbTest extends DbTestBase {

    @Autowired
    private MarketingTemplateService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void batchDelete_stopsRunnableTasksReferencingDeletedTemplateOnly() {
        String suffix = String.valueOf(System.nanoTime());
        long deletedTemplateId = insertTemplate("删除联动模板-" + suffix);
        long otherTemplateId = insertTemplate("保留模板-" + suffix);
        long pendingTaskId = insertTask("待启动任务-" + suffix, deletedTemplateId, MarketingTaskStatus.PENDING);
        long sendingTaskId = insertTask("发送中任务-" + suffix, deletedTemplateId, MarketingTaskStatus.SENDING);
        long successTaskId = insertTask("已成功任务-" + suffix, deletedTemplateId, MarketingTaskStatus.SUCCESS);
        long otherSendingTaskId = insertTask("其它模板任务-" + suffix, otherTemplateId, MarketingTaskStatus.SENDING);
        long textTaskId = insertTextTask("文本任务-" + suffix, MarketingTaskStatus.SENDING);

        service.batchDelete(List.of(deletedTemplateId));

        assertThat(templateDeletedAt(deletedTemplateId)).isNotNull();
        assertThat(templateDeletedAt(otherTemplateId)).isNull();
        assertTaskStatus(pendingTaskId, MarketingTaskStatus.STOPPED);
        assertTaskStatus(sendingTaskId, MarketingTaskStatus.STOPPED);
        assertTaskStatus(successTaskId, MarketingTaskStatus.SUCCESS);
        assertTaskStatus(otherSendingTaskId, MarketingTaskStatus.SENDING);
        assertTaskStatus(textTaskId, MarketingTaskStatus.SENDING);
    }

    private long insertTemplate(String name) {
        long now = System.currentTimeMillis();
        return insertAndReturnId("""
                INSERT INTO marketing_template
                    (tenant_id, template_name, link_mode, text_type, content, body_text, created_at, updated_at)
                VALUES (?, ?, 1, 'PROMO', '内容', '正文', ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, name);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
    }

    private long insertTask(String taskName, long templateId, MarketingTaskStatus status) {
        long now = System.currentTimeMillis();
        return insertAndReturnId("""
                INSERT INTO marketing_task
                    (tenant_id, task_name, account_group_id, account_group_name,
                     marketing_template_id, marketing_template_name, status,
                     selected_account_count, target_group_count, target_pair_count,
                     sent_message_count, failed_message_count, send_per_round, send_interval_seconds,
                     is_online_check_enabled, is_abnormal_group_skipped, is_auto_retry_enabled, retry_limit,
                     created_at, updated_at)
                VALUES (?, ?, 1, '营销账号组', ?, '营销模板', ?, 0, 0, 0, 0, 0, 1, 30, 1, 1, 0, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, taskName);
            ps.setLong(3, templateId);
            ps.setInt(4, status.code());
            ps.setLong(5, now);
            ps.setLong(6, now);
        });
    }

    private long insertTextTask(String taskName, MarketingTaskStatus status) {
        long now = System.currentTimeMillis();
        return insertAndReturnId("""
                INSERT INTO marketing_task
                    (tenant_id, task_name, account_group_id, account_group_name,
                     marketing_template_id, marketing_template_name, send_content_type, text_content, status,
                     selected_account_count, target_group_count, target_pair_count,
                     sent_message_count, failed_message_count, send_per_round, send_interval_seconds,
                     is_online_check_enabled, is_abnormal_group_skipped, is_auto_retry_enabled, retry_limit,
                     created_at, updated_at)
                VALUES (?, ?, 1, '营销账号组', NULL, NULL, 2, 'https://example.com 按普通文字发送',
                        ?, 0, 0, 0, 0, 0, 1, 30, 1, 1, 0, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, taskName);
            ps.setInt(3, status.code());
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
    }

    private Long templateDeletedAt(long templateId) {
        return jdbc.queryForObject("SELECT deleted_at FROM marketing_template WHERE id = ?", Long.class, templateId);
    }

    private void assertTaskStatus(long taskId, MarketingTaskStatus expected) {
        Integer status = jdbc.queryForObject("SELECT status FROM marketing_task WHERE id = ?", Integer.class, taskId);
        assertThat(status).isEqualTo(expected.code());
    }

    private long insertAndReturnId(String sql, SqlBinder binder) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            binder.bind(ps);
            return ps;
        }, keys);
        Number key = keys.getKey();
        assertThat(key).as("generated key for " + sql).isNotNull();
        return key.longValue();
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps) throws java.sql.SQLException;
    }
}
