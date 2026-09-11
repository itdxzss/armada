package com.armada.marketing.script;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.marketing.mapper.ScriptMarketingTaskMapper;
import com.armada.marketing.mapper.ScriptMarketingGroupMapper;
import com.armada.marketing.mapper.ScriptMarketingSendRecordMapper;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.dto.ScriptMarketingStepDTO;
import com.armada.marketing.model.dto.ScriptMarketingQuery;
import com.armada.marketing.model.entity.ScriptMarketingTask;
import com.armada.marketing.model.entity.ScriptMarketingGroup;
import com.armada.marketing.script.service.ScriptMarketingContentService;
import com.armada.marketing.script.service.ScriptMarketingExecutionService;
import com.armada.marketing.script.service.ScriptMarketingPacedExecutionService;
import com.armada.marketing.script.service.ScriptQualificationService;
import com.armada.marketing.script.service.ScriptQualificationException;
import com.armada.marketing.model.vo.ScriptQualificationVO;
import java.util.Map;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.protocol.mapper.ScriptMessageControlMapper;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.platform.protocol.port.ScriptMessageControlPort;
import com.armada.platform.protocol.service.impl.ScriptMessageControlService;
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.paging.PageQuery;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScriptMarketingExecutionTest {
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    ScriptMarketingExecutionService execution;
    ScriptMarketingTaskMapper tasks;
    ScriptMarketingGroupMapper groups;
    ScriptMarketingSendRecordMapper records;
    MessageSendPort sender;
    Long taskId;
    Long groupId;
    long now;

    @BeforeEach void setup() throws Exception {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = context.getBean(JdbcTemplate.class);
        jdbc.execute("DROP ALL OBJECTS");
        String migration = new ClassPathResource("db/migration/V180__script_marketing.sql")
                .getContentAsString(StandardCharsets.UTF_8).split("-- 新菜单")[0];
        // H2 treats bound JSON strings as JSON string literals; production MySQL parses them as arrays.
        for (String statement : migration.replace("steps_json JSON", "steps_json LONGTEXT").split(";")) {
            if (!statement.isBlank()) jdbc.execute(statement);
        }
        String upgrade = new ClassPathResource("db/migration/V186__script_marketing_group_assignment.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        var statements = java.util.regex.Pattern.compile("'(ALTER TABLE .*?)',\\s*'SELECT 1'", java.util.regex.Pattern.DOTALL).matcher(upgrade);
        int altered = 0;
        while (statements.find()) {
            jdbc.execute(statements.group(1).replace("''", "'").replace("bindings_json JSON", "bindings_json LONGTEXT"));
            altered++;
        }
        assertThat(altered).isEqualTo(6);
        jdbc.execute("CREATE TABLE protocol_command_outbox (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT, command_id VARCHAR(64) UNIQUE, aggregate_type VARCHAR(64), status INT, last_error VARCHAR(1024), locked_by VARCHAR(128), locked_at BIGINT, next_retry_at BIGINT, updated_at BIGINT)");
        execution = context.getBean(ScriptMarketingExecutionService.class);
        tasks = context.getBean(ScriptMarketingTaskMapper.class);
        groups = context.getBean(ScriptMarketingGroupMapper.class);
        records = context.getBean(ScriptMarketingSendRecordMapper.class);
        sender = context.getBean(MessageSendPort.class);
        when(sender.enqueue(anyList())).thenAnswer(invocation -> {
            List<MessageSendCommand> commands = invocation.getArgument(0);
            for (var command : commands) jdbc.update("INSERT INTO protocol_command_outbox(tenant_id, command_id, aggregate_type, status) VALUES(?,?,?,0)", TenantContext.get(), command.commandId(), "SCRIPT_MARKETING_SEND_RECORD");
            return new MessageSendEnqueueResult(commands.stream().map(c -> MessageSendEnqueueItem.accepted(c.commandId())).toList());
        });
        TenantContext.set(7L); now = System.currentTimeMillis();
        taskId = seedTask(11L); groupId = groups.list(taskId).get(0).getId();
    }
    @AfterEach void cleanup() { TenantContext.clear(); context.close(); }

    @Test void newTaskRejectsAllGroupsBeforeAnyBindingOrOutboxWhenOneGroupIsShort() {
        useGroupedTask(false);
        assertThatThrownBy(() -> execution.action(taskId, "start", 11L)).isInstanceOf(ScriptQualificationException.class);
        assertThat(tasks.find(taskId).getStatus()).isZero();
        assertThat(groups.find(groupId).getBindingsJson()).isNull();
        assertThat(records.count(taskId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM protocol_command_outbox", Long.class)).isZero();
        verifyNoInteractions(sender);
    }

    @Test void newTaskSubmitsOnPreviousSubmissionTimeWithoutWaitingForReceipt() {
        useGroupedTask(true);
        execution.action(taskId, "start", 11L);
        long firstAt = groups.find(groupId).getNextAt();
        execution.tick(taskId, groupId, firstAt);
        long submittedAt = records.findStep(groupId, 0).getSubmittedAt();
        assertThat(submittedAt).isGreaterThanOrEqualTo(firstAt);
        assertThat(groups.find(groupId).getNextStep()).isEqualTo(1);
        assertThat(groups.find(groupId).getNextAt()).isEqualTo(submittedAt + 10000);
        execution.tick(taskId, groupId, submittedAt + 9999);
        assertThat(records.count(taskId)).isEqualTo(1);
        execution.tick(taskId, groupId, submittedAt + 10000);
        assertThat(records.count(taskId)).isEqualTo(2);
        assertThat(tasks.find(taskId).getStatus()).isEqualTo(1);
        var first = records.findStep(groupId, 0); var second = records.findStep(groupId, 1);
        execution.result(event(second.getCommandId(), true));
        execution.result(event(first.getCommandId(), true));
        execution.result(event(first.getCommandId(), true));
        assertThat(groups.find(groupId).getNextStep()).isEqualTo(2);
        assertThat(tasks.find(taskId).getStatus()).isEqualTo(3);
        assertThat(tasks.summary(taskId).successCount()).isEqualTo(2);
    }

    @Test void resumeDiscardsWaitButPreservesAccountCommandAndOriginalSubmission() {
        useGroupedTask(true); execution.action(taskId, "start", 11L);
        long firstAt = groups.find(groupId).getNextAt();
        execution.tick(taskId, groupId, firstAt);
        var first = records.findStep(groupId, 0);
        String bindings = groups.find(groupId).getBindingsJson();
        execution.action(taskId, "pause", 11L);
        assertThat(outboxStatus(first.getCommandId())).isEqualTo(4);
        execution.action(taskId, "resume", 11L);
        long resumedAt = groups.find(groupId).getNextAt();
        assertThat(resumedAt).isLessThan(firstAt + 10000);
        execution.tick(taskId, groupId, resumedAt);
        assertThat(records.findStep(groupId, 0).getSubmittedAt()).isEqualTo(first.getSubmittedAt());
        assertThat(records.findStep(groupId, 0).getCommandId()).isEqualTo(first.getCommandId());
        assertThat(groups.find(groupId).getBindingsJson()).isEqualTo(bindings);
        assertThat(records.count(taskId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM protocol_command_outbox", Long.class)).isEqualTo(2);
    }

    @Test void scheduledFirstSubmissionRechecksEveryGroupAndPausesWithZeroSending() {
        useGroupedTask(true); execution.action(taskId, "start", 11L);
        useGroupedTask(false);
        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        assertThat(tasks.find(taskId).getStatus()).isEqualTo(2);
        assertThat(groups.find(groupId).getNextAt()).isEqualTo(Long.MAX_VALUE);
        assertThat(records.count(taskId)).isZero();
        verifyNoInteractions(sender);
    }

    @Test void qualificationFailureAfterSubmissionSkipsCurrentItemAndContinuesWithoutPausing() {
        useGroupedTask(true); execution.action(taskId, "start", 11L);
        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        var first = records.findStep(groupId, 0);
        String bindings = groups.find(groupId).getBindingsJson();
        useGroupedTask(false);
        var content = context.getBean(ScriptMarketingContentService.class);
        var steps = new java.util.ArrayList<>(content.decode("[]"));
        steps.add(new ScriptMarketingStepDTO("ADMIN", 1L, steps.get(0).message(), "管理员", 7, 7));
        when(content.decode(anyString())).thenReturn(steps);
        when(context.getBean(AccountProtocolLookupService.class).findOnlineProtocolRefs(List.of(2L)))
                .thenReturn(List.of());

        long failedAt = groups.find(groupId).getNextAt();
        execution.tick(taskId, groupId, failedAt);
        assertThat(groups.find(groupId).getPaused()).isFalse();
        assertThat(tasks.find(taskId).getStatus()).isEqualTo(1);
        var failed = records.findStep(groupId, 1);
        assertThat(failed.getStatus()).isEqualTo(3);
        assertThat(failed.getAccountId()).isEqualTo(2L);
        assertThat(failed.getReason()).contains("已跳过", "离线");
        assertThat(groups.find(groupId).getNextStep()).isEqualTo(2);
        assertThat(groups.find(groupId).getNextAt()).isEqualTo(failed.getFinishedAt() + 7000);
        assertThat(outboxStatus(first.getCommandId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM protocol_command_outbox", Long.class)).isEqualTo(1);
        execution.tick(taskId, groupId, failedAt);
        assertThat(records.count(taskId)).isEqualTo(2);

        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        var third = records.findStep(groupId, 2);
        assertThat(third.getAccountId()).isEqualTo(1L);
        execution.result(event(first.getCommandId(), true));
        execution.result(event(third.getCommandId(), true));
        execution.result(event(failed.getCommandId(), false));
        assertThat(tasks.find(taskId).getStatus()).isEqualTo(3);
        assertThat(tasks.summary(taskId).failedCount()).isEqualTo(1);
        assertThat(tasks.summary(taskId).successCount()).isEqualTo(2);
        assertThat(groups.find(groupId).getBindingsJson()).isEqualTo(bindings);
        assertThat(records.count(taskId)).isEqualTo(3);
        verify(sender, times(2)).enqueue(anyList());
    }

    @Test void otherRoleQualificationFailureDoesNotBlockCurrentHealthySender() {
        useGroupedTask(true); execution.action(taskId, "start", 11L);
        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        useGroupedTask(false);
        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        assertThat(groups.find(groupId).getPaused()).isFalse();
        assertThat(groups.find(groupId).getNextStep()).isEqualTo(2);
        assertThat(records.findStep(groupId, 1).getAccountId()).isEqualTo(2L);
        verify(sender, times(2)).enqueue(anyList());
    }

    @Test void groupedSendFailureCallbackContinuesAndNeverResendsFailedItem() {
        useGroupedTask(true); execution.action(taskId, "start", 11L);
        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        var first = records.findStep(groupId, 0);
        execution.result(event(first.getCommandId(), false));
        execution.result(event(first.getCommandId(), false));
        assertThat(groups.find(groupId).getPaused()).isFalse();
        assertThat(tasks.find(taskId).getStatus()).isEqualTo(1);
        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        execution.result(event(records.findStep(groupId, 1).getCommandId(), true));
        assertThat(tasks.find(taskId).getStatus()).isEqualTo(3);
        assertThat(tasks.summary(taskId).failedCount()).isEqualTo(1);
        assertThat(tasks.summary(taskId).successCount()).isEqualTo(1);
        assertThat(records.count(taskId)).isEqualTo(2);
        verify(sender, times(2)).enqueue(anyList());
    }

    @Test void startedTaskAndGroupCanResumeWithUnavailableRolesWithoutReplacingBindings() {
        useGroupedTask(true); execution.action(taskId, "start", 11L);
        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        var first = records.findStep(groupId, 0);
        String bindings = groups.find(groupId).getBindingsJson();
        execution.groupAction(taskId, groupId, "pause", 11L);
        execution.action(taskId, "pause", 11L);
        useGroupedTask(false);
        execution.action(taskId, "resume", 11L);
        assertThat(groups.find(groupId).getPaused()).isTrue();
        assertThat(outboxStatus(first.getCommandId())).isEqualTo(4);
        execution.groupAction(taskId, groupId, "resume", 11L);
        assertThat(groups.find(groupId).getPaused()).isFalse();
        assertThat(tasks.find(taskId).getStatus()).isEqualTo(1);
        assertThat(groups.find(groupId).getBindingsJson()).isEqualTo(bindings);
        assertThat(outboxStatus(first.getCommandId())).isZero();
        assertThat(records.count(taskId)).isEqualTo(1);
    }

    @Test void perMessageQualificationFailureDoesNotHoldEarlierCommandsOrPauseGroup() {
        useGroupedTask(true); execution.action(taskId, "start", 11L);
        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        var first = records.findStep(groupId, 0);
        doThrow(new com.armada.shared.exception.BusinessException(
                com.armada.shared.exception.ErrorCode.CONFLICT, "原账号没有群内发言权限"))
                .when(context.getBean(ScriptQualificationService.class))
                .requireStepSendable(eq(30L), any(), argThat(step -> "PROMOTER".equals(step.role())));
        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        var failed = records.findStep(groupId, 1);
        assertThat(failed.getStatus()).isEqualTo(3);
        assertThat(failed.getReason()).contains("已跳过", "没有群内发言权限");
        assertThat(groups.find(groupId).getPaused()).isFalse();
        assertThat(outboxStatus(first.getCommandId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM protocol_command_outbox", Long.class)).isEqualTo(1);
        assertThat(tasks.find(taskId).getStatus()).isEqualTo(1);
        execution.result(event(first.getCommandId(), true));
        assertThat(tasks.find(taskId).getStatus()).isEqualTo(3);
        assertThat(tasks.summary(taskId).failedCount()).isEqualTo(1);
        verify(sender, times(1)).enqueue(anyList());
    }

    @Test void severalPendingMessagesCanBeHeldAndClosedWithoutLosingOriginalCommands() {
        useGroupedTask(true); execution.action(taskId, "start", 11L);
        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        var first = records.findStep(groupId, 0); var second = records.findStep(groupId, 1);
        jdbc.update("UPDATE protocol_command_outbox SET status=5 WHERE command_id=?", first.getCommandId());
        execution.action(taskId, "pause", 11L);
        assertThat(records.findCommand(second.getCommandId()).getStatus()).isEqualTo(5);
        execution.action(taskId, "close", 11L);
        assertThat(outboxStatus(first.getCommandId())).isEqualTo(6);
        assertThat(records.findCommand(second.getCommandId()).getStatus()).isEqualTo(3);
        execution.result(event(first.getCommandId(), true));
        assertThat(tasks.find(taskId).getStatus()).isEqualTo(4);
        assertThat(groups.find(groupId).getNextStep()).isEqualTo(2);
        assertThat(records.count(taskId)).isEqualTo(2);
    }

    void useGroupedTask(boolean ready) {
        jdbc.update("UPDATE script_marketing_task SET account_group_id=30 WHERE id=?", taskId);
        var content = context.getBean(ScriptMarketingContentService.class);
        var message = new MarketingTemplateDTO("", 1, null, null, "hello", null, null, null, null, false);
        when(content.decode(anyString())).thenReturn(List.of(
                new ScriptMarketingStepDTO("ADMIN", 1L, message, "管理员", 0, 0),
                new ScriptMarketingStepDTO("PROMOTER", null, message, "推手一", 10, 10)));
        when(content.encodeBindings(anyMap())).thenReturn("fixed-bindings");
        when(content.decodeBindings(anyString())).thenReturn(Map.of("管理员", 1L, "推手一", 2L));
        var report = new ScriptQualificationVO(ready, 3, 1, null, now, List.of(
                new ScriptQualificationVO.Group(40L, "120000@g.us", "群", ready, 1, ready ? 1 : 0,
                        ready ? 0 : 1, 0, 0, 0, ready ? List.of() : List.of("缺 1 个推手"))));
        when(context.getBean(ScriptQualificationService.class).inspect(anyLong(), anyList(), anyList(), anyBoolean()))
                .thenReturn(new ScriptQualificationService.Preparation(report, Map.of(40L, Map.of("管理员", 1L, "推手一", 2L))));
    }

    @Test void completedSubmissionStillScansPendingDeadlinesAndLateResultNeverResubmits() {
        useGroupedTask(true); execution.action(taskId, "start", 11L);
        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        var last = records.findStep(groupId, 1);
        jdbc.update("UPDATE protocol_command_outbox SET status=5 WHERE command_id=?", last.getCommandId());
        assertThat(groups.find(groupId).getNextAt()).isEqualTo(Long.MAX_VALUE);
        assertThat(groups.due(last.getResultDeadlineAt(), 100)).extracting(ScriptMarketingGroup::getId).contains(groupId);
        execution.tick(taskId, groupId, last.getResultDeadlineAt());
        assertThat(records.findCommand(last.getCommandId()).getStatus()).isEqualTo(4);
        assertThat(tasks.find(taskId).getStatus()).isEqualTo(3);
        execution.result(event(last.getCommandId(), true));
        assertThat(records.findCommand(last.getCommandId()).getStatus()).isEqualTo(2);
        assertThat(groups.find(groupId).getNextStep()).isEqualTo(2);
        assertThat(records.count(taskId)).isEqualTo(2);
        verify(sender, times(2)).enqueue(anyList());
    }

    @Test void singleGroupResumeCannotBypassTaskPauseOrCutoffAndKeepsOriginalBinding() {
        useGroupedTask(true); execution.action(taskId, "start", 11L);
        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        var original = records.findStep(groupId, 0);
        String bindings = groups.find(groupId).getBindingsJson();
        execution.groupAction(taskId, groupId, "pause", 11L);
        assertThat(groups.find(groupId).getPaused()).isTrue();
        assertThat(tasks.find(taskId).getStatus()).isEqualTo(1);
        assertThat(outboxStatus(original.getCommandId())).isEqualTo(4);
        execution.action(taskId, "pause", 11L);
        assertThatThrownBy(() -> execution.groupAction(taskId, groupId, "resume", 11L))
                .hasMessageContaining("请先继续整个任务");
        execution.action(taskId, "resume", 11L);
        assertThat(outboxStatus(original.getCommandId())).isEqualTo(4);
        jdbc.update("UPDATE script_marketing_task SET end_at=? WHERE id=?", System.currentTimeMillis() - 1, taskId);
        assertThatThrownBy(() -> execution.groupAction(taskId, groupId, "resume", 11L))
                .hasMessageContaining("截止时间");
        assertThat(outboxStatus(original.getCommandId())).isEqualTo(4);
        jdbc.update("UPDATE script_marketing_task SET end_at=NULL WHERE id=?", taskId);
        execution.groupAction(taskId, groupId, "resume", 11L);
        assertThat(groups.find(groupId).getPaused()).isFalse();
        assertThat(groups.find(groupId).getBindingsJson()).isEqualTo(bindings);
        assertThat(records.findCommand(original.getCommandId()).getSubmittedAt()).isEqualTo(original.getSubmittedAt());
        assertThat(outboxStatus(original.getCommandId())).isZero();
    }

    @Test void failureContinuesAndDuplicateCallbacksNeverAdvanceTwice() {
        execution.action(taskId, "start", 11L);
        execution.tick(taskId, groupId, now + 1);
        var first = records.findStep(groupId, 0);
        execution.result(event(first.getCommandId(), false));
        execution.result(event(first.getCommandId(), false));
        assertThat(groups.find(groupId).getNextStep()).isEqualTo(1);
        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        var second = records.findStep(groupId, 1);
        execution.result(event(second.getCommandId(), false));
        assertThat(tasks.find(taskId).getStatus()).isEqualTo(3);
        assertThat(tasks.summary(taskId).failedCount()).isEqualTo(2);
        execution.action(taskId, "close", 11L);
        assertThatThrownBy(() -> execution.action(taskId, "resume", 11L)).hasMessageContaining("暂停");
        assertThat(records.count(taskId)).isEqualTo(2);
    }
    @Test void pauseAndResumeHeldCommandUseExactlyTheSameOutboxRow() {
        execution.action(taskId, "start", 11L); execution.tick(taskId, groupId, now + 1);
        String command = records.findStep(groupId, 0).getCommandId();
        execution.action(taskId, "pause", 11L);
        assertThat(records.findCommand(command).getStatus()).isEqualTo(5);
        assertThat(outboxStatus(command)).isEqualTo(4);
        execution.action(taskId, "resume", 11L);
        execution.tick(taskId, groupId, System.currentTimeMillis());
        assertThat(outboxStatus(command)).isZero();
        assertThat(records.findStep(groupId, 0).getCommandId()).isEqualTo(command);
        verify(sender, times(1)).enqueue(anyList());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM protocol_command_outbox", Long.class)).isEqualTo(1L);
    }
    @Test void inFlightResultWhilePausedPreservesRemainingWaitAndDoesNotResend() {
        execution.action(taskId, "start", 11L); execution.tick(taskId, groupId, now + 1);
        String command = records.findStep(groupId, 0).getCommandId();
        jdbc.update("UPDATE protocol_command_outbox SET status=2 WHERE command_id=?", command);
        execution.action(taskId, "pause", 11L); execution.result(event(command, true));
        var paused = groups.find(groupId);
        assertThat(paused.getNextStep()).isEqualTo(1);
        assertThat(paused.getRemainingWaitMs()).isEqualTo(10000);
        execution.tick(taskId, groupId, Long.MAX_VALUE - 1);
        verify(sender, times(1)).enqueue(anyList());
        execution.action(taskId, "resume", 11L);
        var resumed = groups.find(groupId);
        assertThat(resumed.getNextAt()).isGreaterThanOrEqualTo(System.currentTimeMillis() + 9500);
        execution.tick(taskId, groupId, resumed.getNextAt());
        assertThat(records.count(taskId)).isEqualTo(2);
        execution.result(event(command, true));
        assertThat(groups.find(groupId).getNextStep()).isEqualTo(1);
    }
    @Test void sentTimeoutIsUnknownAndLateSuccessDoesNotAdvanceNextStepAgain() {
        execution.action(taskId, "start", 11L); execution.tick(taskId, groupId, now + 1);
        String command = records.findStep(groupId, 0).getCommandId();
        jdbc.update("UPDATE protocol_command_outbox SET status=2 WHERE command_id=?", command);
        execution.tick(taskId, groupId, now + 1 + ScriptMarketingExecutionService.RESULT_TIMEOUT_MS);
        assertThat(tasks.summary(taskId).unknownCount()).isEqualTo(1);
        assertThat(groups.find(groupId).getNextStep()).isEqualTo(1);
        execution.result(event(command, true));
        assertThat(tasks.summary(taskId).unknownCount()).isZero();
        assertThat(tasks.summary(taskId).successCount()).isEqualTo(1);
        assertThat(groups.find(groupId).getNextStep()).isEqualTo(1);
        verify(sender, times(1)).enqueue(anyList());
    }
    @Test void pendingTimeoutCancelsBeforeAdvancingAndCannotBeResumed() {
        execution.action(taskId, "start", 11L); execution.tick(taskId, groupId, now + 1);
        String command = records.findStep(groupId, 0).getCommandId();
        execution.tick(taskId, groupId, now + 1 + ScriptMarketingExecutionService.RESULT_TIMEOUT_MS);
        assertThat(records.findCommand(command).getStatus()).isEqualTo(3);
        assertThat(outboxStatus(command)).isEqualTo(4);
        assertThat(context.getBean(ScriptMessageControlPort.class).resume(command)).isFalse();
        execution.tick(taskId, groupId, groups.find(groupId).getNextAt());
        assertThat(records.count(taskId)).isEqualTo(2);
    }
    @Test void closingDispatchingCommandPreventsTransportRetryAndKeepsLateOutcome() {
        execution.action(taskId, "start", 11L); execution.tick(taskId, groupId, now + 1);
        String command = records.findStep(groupId, 0).getCommandId();
        jdbc.update("UPDATE protocol_command_outbox SET status=5 WHERE command_id=?", command);
        execution.action(taskId, "close", 11L);
        assertThat(outboxStatus(command)).isEqualTo(6);
        execution.result(event(command, true));
        assertThat(tasks.find(taskId).getStatus()).isEqualTo(4);
        assertThat(tasks.summary(taskId).successCount()).isEqualTo(1);
        assertThat(groups.find(groupId).getNextStep()).isZero();
    }
    @Test void tenantOwnerPaginationAndUniqueConstraintUseRealSql() {
        seedTask(12L); var query = new ScriptMarketingQuery(); query.setPageSize(1);
        assertThat(tasks.count(query, 11L)).isEqualTo(1);
        assertThat(tasks.page(query, 11L)).extracting(v -> v.id()).containsExactly(taskId);
        assertThatThrownBy(() -> execution.action(taskId, "start", 12L)).hasMessageContaining("无权");
        execution.action(taskId, "start", 11L); execution.tick(taskId, groupId, now + 1);
        var first = records.findStep(groupId, 0); first.setId(null); first.setCommandId("different-command");
        assertThatThrownBy(() -> records.insert(first)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        TenantContext.set(8L);
        assertThat(tasks.find(taskId)).isNull(); assertThat(groups.find(groupId)).isNull();
        assertThat(records.page(taskId, new PageQuery())).isEmpty();
        assertThat(tasks.page(query, 11L)).isEmpty();
        TenantContext.clear(); assertThat(tasks.find(taskId)).isNull();
    }
    @Test void queueFailureRollsBackIntentOutboxAndProgress() {
        when(sender.enqueue(anyList())).thenThrow(new IllegalStateException("database unavailable"));
        execution.action(taskId, "start", 11L);
        assertThatThrownBy(() -> execution.tick(taskId, groupId, now + 1)).hasMessageContaining("database unavailable");
        assertThat(records.count(taskId)).isZero();
        assertThat(groups.find(groupId).getNextStep()).isZero();
    }
    @Test void enqueueExceptionIsRecordedAfterRollbackAndLaterStepContinues() {
        execution.action(taskId, "start", 11L);
        when(sender.enqueue(anyList())).thenThrow(new IllegalStateException("queue unavailable"));
        new com.armada.marketing.script.scheduler.ScriptMarketingScheduler(groups, execution).tick();
        assertThat(records.findStep(groupId, 0).getStatus()).isEqualTo(3);
        assertThat(groups.find(groupId).getNextStep()).isEqualTo(1);
        assertThat(records.count(taskId)).isEqualTo(1);
        execution.recordUnsubmittedFailure(taskId, groupId, 0, now, "duplicate failure");
        assertThat(groups.find(groupId).getNextStep()).isEqualTo(1);
        assertThatThrownBy(() -> execution.tick(taskId, groupId, groups.find(groupId).getNextAt()))
                .hasMessageContaining("queue unavailable");
        execution.recordUnsubmittedFailure(taskId, groupId, 1, System.currentTimeMillis(), "queue unavailable");
        assertThat(tasks.find(taskId).getStatus()).isEqualTo(3);
        assertThat(tasks.summary(taskId).failedCount()).isEqualTo(2);
    }
    @Test void competingSchedulerWaitsForTaskLockThenObservesExistingIntent() throws Exception {
        execution.action(taskId, "start", 11L);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                TenantContext.set(7L);
                try { new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
                    tasks.lock(taskId); locked.countDown();
                    try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException e) { throw new IllegalStateException(e); }
                    execution.tick(taskId, groupId, now + 1);
                }); } finally { TenantContext.clear(); }
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var started = new CountDownLatch(1);
            var second = pool.submit(() -> {
                TenantContext.set(7L); started.countDown();
                try { execution.tick(taskId, groupId, now + 1); }
                finally { TenantContext.clear(); }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertThat(records.count(taskId)).isEqualTo(1); verify(sender, times(1)).enqueue(anyList());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void duplicateResultsWaitingOnTaskLockKeepOneAdvance() throws Exception {
        execution.action(taskId, "start", 11L); execution.tick(taskId, groupId, now + 1);
        String command = records.findStep(groupId, 0).getCommandId();
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(3);
        try {
            var holder = pool.submit(() -> {
                TenantContext.set(7L);
                try { new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
                    tasks.lock(taskId); locked.countDown();
                    try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                }); } finally { TenantContext.clear(); }
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Runnable callback = () -> {
                TenantContext.set(7L);
                try { execution.result(event(command, true)); } finally { TenantContext.clear(); }
            };
            var first = pool.submit(callback); var second = pool.submit(callback);
            assertThatThrownBy(() -> first.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); holder.get(5, TimeUnit.SECONDS); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertThat(groups.find(groupId).getNextStep()).isEqualTo(1);
            assertThat(tasks.summary(taskId).successCount()).isEqualTo(1);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    Long seedTask(Long owner) {
        var task = new ScriptMarketingTask(); task.setTenantId(7L); task.setCreatedBy(owner);
        task.setTaskName("test script"); task.setStepsJson("[]"); task.setStatus(0);
        task.setIntervalSeconds(10); task.setStartAt(now); task.setCreatedAt(now); task.setUpdatedAt(now);
        tasks.insert(task);
        var group = new ScriptMarketingGroup(); group.setTenantId(7L); group.setTaskId(task.getId());
        group.setGroupLinkId(40L); group.setGroupJid("120000@g.us"); group.setGroupName("group");
        group.setNextStep(0); group.setNextAt(now); group.setRemainingWaitMs(0L); groups.insert(group);
        return task.getId();
    }
    int outboxStatus(String command) {
        return jdbc.queryForObject("SELECT status FROM protocol_command_outbox WHERE command_id=?", Integer.class, command);
    }
    ProtocolMessageSendResultReportedEvent event(String command, boolean success) {
        return new ProtocolMessageSendResultReportedEvent("event", 7L, null, null, null, null,
                "account", "120000@g.us", command, success, success ? "wamid.ok" : null, success ? null : "SEND_FAILED",
                null, now, "worker", null, null, "script_marketing", null, null, null,
                null, null, null, null, null, "120000@g.us", "GROUP",
                null, null, null, null, null, true);
    }
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({MyBatisConfig.class, ScriptMarketingPacedExecutionService.class})
    static class Config {
        @Bean DataSource dataSource() {
            var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:script_execution;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"); return ds;
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean DataSourceTransactionManager tx(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionFactory factory(DataSource ds, MybatisPlusInterceptor plugin) throws Exception {
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); config.setUseGeneratedKeys(true);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config); factory.setPlugins(plugin);
            factory.setMapperLocations(new ClassPathResource("mapper/marketing/ScriptMarketingTaskMapper.xml"),
                    new ClassPathResource("mapper/marketing/ScriptMarketingGroupMapper.xml"),
                    new ClassPathResource("mapper/marketing/ScriptMarketingSendRecordMapper.xml"),
                    new ClassPathResource("mapper/platform/ScriptMessageControlMapper.xml"));
            return factory.getObject();
        }
        @Bean SqlSessionTemplate session(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean ScriptMarketingTaskMapper tasks(SqlSessionTemplate s) { return s.getMapper(ScriptMarketingTaskMapper.class); }
        @Bean ScriptMarketingGroupMapper groups(SqlSessionTemplate s) { return s.getMapper(ScriptMarketingGroupMapper.class); }
        @Bean ScriptMarketingSendRecordMapper records(SqlSessionTemplate s) { return s.getMapper(ScriptMarketingSendRecordMapper.class); }
        @Bean ScriptMessageControlMapper commands(SqlSessionTemplate s) { return s.getMapper(ScriptMessageControlMapper.class); }
        @Bean ScriptMessageControlPort control(ScriptMessageControlMapper mapper) { return new ScriptMessageControlService(mapper); }
        @Bean MessageSendPort sender() { return mock(MessageSendPort.class); }
        @Bean ScriptQualificationService qualification() { return mock(ScriptQualificationService.class); }
        @Bean AccountProtocolLookupService accounts() {
            var service = mock(AccountProtocolLookupService.class);
            when(service.findOnlineProtocolRefs(anyList())).thenAnswer(call -> {
                List<Long> ids = call.getArgument(0);
                return ids.stream().map(id -> new ProtocolAccountRef(id, ProtocolBackend.WEB,
                        "account_" + id, "1555000000" + id)).toList();
            });
            return service;
        }
        @Bean ScriptMarketingContentService content() {
            var service = mock(ScriptMarketingContentService.class);
            var message = new MarketingTemplateDTO("", 1, null, null, "hello", null, null, null, null, false);
            when(service.decode(anyString())).thenReturn(List.of(new ScriptMarketingStepDTO("ADMIN", 1L, message, null, null, null), new ScriptMarketingStepDTO("PROMOTER", 2L, message, null, null, null)));
            when(service.payload(any())).thenReturn(new MessageSendCommand.MessagePayload(MessageType.TEXT, new MessageSendCommand.MessageContent("hello", null, null, null), false));
            return service;
        }
        @Bean ScriptMarketingExecutionService execution(ScriptMarketingTaskMapper tasks, ScriptMarketingGroupMapper groups,
                ScriptMarketingSendRecordMapper records, ScriptMarketingContentService content, AccountProtocolLookupService accounts,
                MessageSendPort sender, ScriptMessageControlPort control, DataSourceTransactionManager transactionManager,
                ScriptMarketingPacedExecutionService paced) {
            return new ScriptMarketingExecutionService(tasks, groups, records, content, accounts, sender, control, transactionManager, paced);
        }
    }
}
