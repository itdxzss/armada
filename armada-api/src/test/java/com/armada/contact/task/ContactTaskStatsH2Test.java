package com.armada.contact.task;

import com.armada.contact.task.controller.ContactTaskStatsController;
import com.armada.contact.task.controller.ContactTaskRecipientController;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.armada.boot.config.MyBatisConfig;
import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.mapper.ContactTaskStatsMapper;
import com.armada.contact.task.model.dto.ContactTaskQuery;
import com.armada.contact.task.model.dto.ContactTaskRecipientQuery;
import com.armada.contact.task.model.vo.ContactTaskMetricsVO;
import com.armada.contact.task.service.ContactTaskRecipientService;
import com.armada.contact.task.service.ContactTaskSendResultSink;
import com.armada.contact.task.service.ContactTaskService;
import com.armada.contact.task.service.ContactTaskStatsService;
import com.armada.contact.task.service.impl.ContactTaskServiceImpl;
import com.armada.platform.kafka.consumer.message.ProtocolMessageAckEvent;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真正执行聚合、租户插件、排序分页与迟到回执，验证三个视图使用同一 recipient 口径。 */
@SpringJUnitConfig(ContactTaskStatsH2Test.Config.class)
class ContactTaskStatsH2Test {
    @Autowired DataSource source;
    @Autowired SqlSessionTemplate session;
    @Autowired ContactTaskStatsService stats;
    @Autowired ContactTaskRecipientService recipients;
    @Autowired ContactTaskService tasks;
    @Autowired ContactTaskStatsController statsController;
    @Autowired ContactTaskRecipientController recipientController;
    private JdbcTemplate jdbc;

    @BeforeEach void prepare() throws Exception {
        TenantContext.set(7L);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V163__contact_friend_task.sql"));
        }
        jdbc.execute("ALTER TABLE contact_friend_task ADD current_round_no BIGINT DEFAULT 0");
        jdbc.execute("ALTER TABLE contact_friend_task_account ADD stop_reason VARCHAR(255)");
        jdbc.execute("ALTER TABLE contact_friend_task_recipient ADD delivered_at BIGINT");
        jdbc.execute("ALTER TABLE contact_friend_task_recipient ADD read_at BIGINT");
        jdbc.execute("ALTER TABLE contact_friend_task_recipient ADD command_id VARCHAR(64)");
        jdbc.execute("ALTER TABLE contact_friend_task_recipient ADD round_no BIGINT");
        jdbc.execute("ALTER TABLE contact_friend_task_recipient ALTER COLUMN contact_phone DROP NOT NULL");
        jdbc.update("INSERT INTO contact_friend_task(id,tenant_id,name,message_type,content,created_at,updated_at,run_status) VALUES(1,7,'test',1,'test',1,1,2),(2,7,'empty',1,'test',1,1,0),(3,8,'other',1,'test',1,1,2)");
        jdbc.update("INSERT INTO contact_friend_task_account(id,tenant_id,task_id,account_id,need_send_num,state,created_at,updated_at) VALUES(10,7,1,110,50,'DONE',1,1),(11,7,1,111,50,'FAILED',1,1),(12,7,2,112,0,'PREPARING',1,1),(99,8,1,199,1,'FAILED',1,1)");
        for (int i = 1; i <= 100; i++) {
            String status = i <= 80 ? "SUCCESS" : i <= 90 ? "FAILED" : i <= 95 ? "UNKNOWN" : "SKIPPED";
            jdbc.update("INSERT INTO contact_friend_task_recipient(id,tenant_id,task_id,task_account_id,contact_jid,send_status,attempt_count,delivered_at,read_at,command_id,error_code,created_at,updated_at) VALUES(?,7,1,?,?,?, ?,?,?,?, ?,1,1)",
                    i, i % 2 == 1 ? 10 : 11, "fixture-" + i + "@lid", status,
                    i <= 95 ? 1 : 0, i <= 60 ? 100L : null, i <= 25 ? 100L : null,
                    "cmd-" + i, i <= 80 ? null : "TEST_REASON");
        }
        jdbc.update("INSERT INTO contact_friend_task_recipient(id,tenant_id,task_id,task_account_id,contact_jid,send_status,attempt_count,delivered_at,read_at,created_at,updated_at) VALUES(999,8,1,99,'other@lid','SUCCESS',1,100,100,1,1)");
    }

    @AfterEach void clear() { TenantContext.clear(); SecurityContextHolder.clearContext(); }

    @Test void cumulativeReceiptsAndProcessedBucketsAreNotAddedTogether() {
        var result = stats.stats(1L);
        assertThat(result.metrics()).isEqualTo(new ContactTaskMetricsVO(1L,100,95,80,60,25,10,5,5,100,0,0,0));
        assertThat(result.accounts().selectedAccountNum()).isEqualTo(2);
        assertThat(result.accounts().failedAccountNum()).isEqualTo(1);
        assertThat(result.reasons()).extracting("count").containsExactly(10L,5L,5L);
    }

    @Test void taskPageContainsMetricsForExactlyItsVisibleTasks() {
        var first = tasks.list(new ContactTaskQuery(null,null,null,null,1,1));
        var second = tasks.list(new ContactTaskQuery(null,null,null,null,2,1));
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.list()).extracting("id").containsExactly(2L);
        assertThat(first.list().get(0).stats().metrics().plannedNum()).isZero();
        assertThat(second.list().get(0).stats().metrics().confirmedNum()).isEqualTo(80);
    }

    @Test void emptyAndPreparingTasksAreRealZeroCounts() {
        var result = stats.stats(2L);
        assertThat(result.metrics()).isEqualTo(ContactTaskMetricsVO.empty(2L));
        assertThat(result.accounts().preparingAccountNum()).isOne();
        assertThat(result.accounts().readyAccountNum()).isZero();
        assertThat(result.reasons()).isEmpty();
    }

    @Test void filtersMatchCumulativeAndExclusiveMetricsAcrossPages() {
        var query = new ContactTaskRecipientQuery();
        for (var filter : List.of("CONFIRMED", "SINGLE_ONLY", "DELIVERED", "DELIVERED_UNREAD", "READ")) {
            query.setReceiptStatus(filter);
            long expected = switch (filter) { case "CONFIRMED" -> 80; case "SINGLE_ONLY" -> 20;
                case "DELIVERED" -> 60; case "DELIVERED_UNREAD" -> 35; default -> 25; };
            assertThat(recipients.list(1L,query).total()).isEqualTo(expected);
        }
        query.setReceiptStatus("READ"); query.setPage(2);
        var second = recipients.list(1L,query);
        assertThat(second.list()).hasSize(5);
        assertThat(second.list()).extracting("id").containsExactly(21L,22L,23L,24L,25L);
        query.setPage(Integer.MAX_VALUE);
        assertThat(recipients.list(1L,query).list()).isEmpty();
    }

    @Test void accountSortUsesAllAccountsBeforePaginationAndMatchesTheirRecipients() {
        var first = stats.accounts(1L,"unknownNum","desc",1,1);
        var second = stats.accounts(1L,"unknownNum","desc",2,1);
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.list().get(0).taskAccountId()).isEqualTo(10);
        assertThat(first.list().get(0).metrics().unknownNum()).isEqualTo(3);
        assertThat(second.list().get(0).taskAccountId()).isEqualTo(11);
        var query = new ContactTaskRecipientQuery();query.setTaskAccountId(10L);query.setSendStatus("UNKNOWN");
        assertThat(recipients.list(1L,query).total()).isEqualTo(3);
        assertThat(stats.accounts(1L,"1=1",";DROP",1,20).list()).extracting("taskAccountId").containsExactly(11L,10L);
    }

    @Test void invisibleTasksAndMismatchedAccountsAreRejected() {
        assertThatThrownBy(()->stats.stats(3L)).isInstanceOf(BusinessException.class);
        var query = new ContactTaskRecipientQuery();query.setTaskAccountId(12L);
        assertThatThrownBy(()->recipients.list(1L,query)).isInstanceOf(BusinessException.class);
        query.setTaskAccountId(99L);
        assertThatThrownBy(()->recipients.list(1L,query)).isInstanceOf(BusinessException.class);
        query.setTaskAccountId(null);query.setReceiptStatus("anything");
        assertThatThrownBy(()->recipients.list(1L,query)).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE contact_friend_task SET deleted_at=123 WHERE id=1");
        assertThatThrownBy(()->stats.stats(1L)).isInstanceOf(BusinessException.class);
    }

    @Test void lateReadAfterTaskCompletionResolvesUnknownOnceWithoutChangingProgress() {
        var sink = new ContactTaskSendResultSink(session.getMapper(ContactFriendTaskMapper.class),
                session.getMapper(ContactFriendTaskAccountMapper.class),
                session.getMapper(ContactFriendTaskRecipientMapper.class), ()->200L);
        var event = new ProtocolMessageAckEvent("ack",7L,"contact_task",null,null,"cmd-91",110L,
                "android","test","fixture-91@lid","PRIVATE","message","READ",true,null,null,200L,"worker");
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.executeWithoutResult(s->{sink.handleAck(event);sink.handleAck(event);});
        var m = stats.stats(1L).metrics();
        assertThat(m.confirmedNum()).isEqualTo(81);assertThat(m.deliveredNum()).isEqualTo(61);
        assertThat(m.readNum()).isEqualTo(26);assertThat(m.unknownNum()).isEqualTo(4);
        assertThat(m.processedNum()).isEqualTo(100);assertThat(stats.stats(1L).runStatus()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM contact_friend_task_recipient WHERE id=91",Integer.class)).isOne();
    }

    @Test void unknownsAreProcessedButNotSingleTicksAndContradictionsAreFlagged() {
        jdbc.update("UPDATE contact_friend_task_recipient SET send_status='UNKNOWN',delivered_at=NULL,read_at=NULL WHERE tenant_id=7 AND task_id=1");
        var m = stats.stats(1L).metrics();
        assertThat(m.unknownNum()).isEqualTo(100);assertThat(m.processedNum()).isEqualTo(100);
        assertThat(m.confirmedNum()).isZero();assertThat(m.deliveredNum()).isZero();
        jdbc.update("UPDATE contact_friend_task_recipient SET read_at=100 WHERE id=1");
        assertThat(stats.stats(1L).metrics().inconsistentNum()).isOne();
    }

    @Test void newRoutesBindFiltersAndReturnConsistentFacts() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "fixture", "", List.of(new SimpleGrantedAuthority("tenant:contact_task:view"))));
        var mvc = MockMvcBuilders.standaloneSetup(statsController, recipientController).build();
        mvc.perform(get("/api/contact-tasks/1/stats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics.confirmedNum").value(80));
        mvc.perform(get("/api/contact-tasks/1/recipients").param("receiptStatus","READ")
                .param("page","2").param("pageSize","20")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(25))
                .andExpect(jsonPath("$.data.list[0].id").value(21));
        mvc.perform(get("/api/contact-tasks/1/accounts/10/recipients"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(50));
    }

    @Test void bothReadRoutesRequireContactTaskViewPermission() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "fixture", "", List.of(new SimpleGrantedAuthority("tenant:unrelated:view"))));
        assertThatThrownBy(()->statsController.stats(1L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->recipientController.listTask(1L,new ContactTaskRecipientQuery()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Configuration(proxyBeanMethods=false)
    @EnableMethodSecurity
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class Config {
        @Bean DataSource source() { var db=new JdbcDataSource();db.setURL("jdbc:h2:mem:contact_stats;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");return db; }
        @Bean SqlSessionFactory factory(DataSource source, MybatisPlusInterceptor plugin) throws Exception {
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);factory.setPlugins(plugin);
            factory.setMapperLocations(new ClassPathResource("mapper/contact/ContactFriendTaskMapper.xml"),
                    new ClassPathResource("mapper/contact/ContactFriendTaskAccountMapper.xml"),
                    new ClassPathResource("mapper/contact/ContactFriendTaskRecipientMapper.xml"),
                    new ClassPathResource("mapper/contact/ContactTaskStatsMapper.xml"));return factory.getObject();
        }
        @Bean SqlSessionTemplate session(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean ContactTaskStatsService stats(SqlSessionTemplate s) { return new ContactTaskStatsService(s.getMapper(ContactFriendTaskMapper.class),s.getMapper(ContactFriendTaskAccountMapper.class),s.getMapper(ContactTaskStatsMapper.class)); }
        @Bean ContactTaskRecipientService recipients(SqlSessionTemplate s) { return new ContactTaskRecipientService(s.getMapper(ContactFriendTaskMapper.class),s.getMapper(ContactTaskStatsMapper.class)); }
        @Bean ContactTaskStatsController statsController(ContactTaskStatsService stats) { return new ContactTaskStatsController(stats); }
        @Bean ContactTaskRecipientController recipientController(ContactTaskRecipientService recipients) { return new ContactTaskRecipientController(recipients); }
        @Bean ContactTaskService tasks(SqlSessionTemplate s,ContactTaskStatsService stats) { return new ContactTaskServiceImpl(s.getMapper(ContactFriendTaskMapper.class),stats,null,null,null,TenantContext::get,()->100L); }
    }
}
