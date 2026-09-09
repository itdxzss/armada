package com.armada.contact.task;

import com.armada.boot.config.MyBatisConfig;
import com.armada.contact.task.mapper.*;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.contact.task.service.ContactTaskSendResultSink;
import com.armada.platform.kafka.consumer.message.ProtocolMessageAckEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实 Mapper、租户拦截与事务验证乱序回执、未知终态和重复投递。 */
@SpringJUnitConfig(ContactTaskReceiptH2Test.Config.class)
class ContactTaskReceiptH2Test {
    @Autowired DataSource dataSource;
    @Autowired SqlSessionTemplate session;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ContactFriendTaskRecipientMapper recipients;
    private ContactTaskSendResultSink sink;

    @BeforeEach void setUp() {
        TenantContext.set(7L);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE contact_friend_task(id BIGINT PRIMARY KEY, tenant_id BIGINT, success_message_num INT DEFAULT 0, deleted_at BIGINT, updated_at BIGINT)");
        jdbc.execute("CREATE TABLE contact_friend_task_account(id BIGINT PRIMARY KEY, tenant_id BIGINT, task_id BIGINT, account_id BIGINT, state VARCHAR(16), sent_num INT DEFAULT 0, fail_num INT DEFAULT 0, stop_reason VARCHAR(255), updated_at BIGINT)");
        jdbc.execute("""
            CREATE TABLE contact_friend_task_recipient (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT, task_id BIGINT, task_account_id BIGINT,
              contact_phone VARCHAR(32), contact_jid VARCHAR(64) NOT NULL, contact_named INT,
              send_status VARCHAR(16), attempt_count INT, protocol_message_id VARCHAR(128),
              error_code VARCHAR(64), error_desc VARCHAR(255), first_sent_at BIGINT, last_attempt_at BIGINT,
              delivered_at BIGINT, read_at BIGINT, round_no BIGINT, command_id VARCHAR(64),
              created_at BIGINT, updated_at BIGINT,
              UNIQUE(tenant_id, task_id, task_account_id, contact_jid))
            """);
        jdbc.update("INSERT INTO contact_friend_task(id, tenant_id) VALUES(1, 7)");
        jdbc.update("INSERT INTO contact_friend_task_account(id, tenant_id, task_id, account_id, state) VALUES(10, 7, 1, 20, 'RUNNING')");
        jdbc.update("INSERT INTO contact_friend_task_recipient(id, tenant_id, task_id, task_account_id, contact_jid, send_status, command_id, updated_at) VALUES(100, 7, 1, 10, '123456@lid', 'SENDING', 'cmd', 1)");
        jdbc.update("INSERT INTO contact_friend_task_recipient(id, tenant_id, task_id, task_account_id, contact_jid, send_status, updated_at) VALUES(101, 7, 1, 10, '654321@lid', 'PENDING', 1)");
        recipients = session.getMapper(ContactFriendTaskRecipientMapper.class);
        sink = new ContactTaskSendResultSink(session.getMapper(ContactFriendTaskMapper.class),
                session.getMapper(ContactFriendTaskAccountMapper.class), recipients, () -> 1000);
    }
    @AfterEach void tearDown() { TenantContext.clear(); }

    private ProtocolMessageSendResultReportedEvent result(boolean success, String code) {
        return new ProtocolMessageSendResultReportedEvent("event", 7L, null, null, null, 1L,
                "account", "123456@lid", "cmd", success, "msg", code, code,
                1000L, "worker", null, null, "contact_task", null, null, null, null, null,
                1L, 10L, 100L);
    }
    private ProtocolMessageAckEvent ack(long tenant, String messageId, String status) {
        return new ProtocolMessageAckEvent("ack", tenant, "contact_task", null, null, "cmd",
                20L, "android", "account", "123456@lid", "PRIVATE", messageId, status,
                true, null, null, 1100L, "worker");
    }
    private ContactFriendTaskRecipient row() { return tx.execute(s -> recipients.selectById(100L)); }
    private int sent() { return jdbc.queryForObject("SELECT success_message_num FROM contact_friend_task WHERE id = 1", Integer.class); }

    @Test void readBeforeSendResultPromotesOnlyOnceAndNeverRegresses() {
        tx.executeWithoutResult(s -> sink.handleAck(ack(7L, "msg", "READ")));
        tx.executeWithoutResult(s -> sink.handleSendResultReported(result(true, null)));
        tx.executeWithoutResult(s -> sink.handleAck(ack(7L, "msg", "DELIVERED")));
        tx.executeWithoutResult(s -> sink.handleSendResultReported(result(false, "SEND_RESULT_UNKNOWN")));
        assertThat(row().getSendStatus()).isEqualTo("SUCCESS");
        assertThat(row().getReadAt()).isEqualTo(1100);
        assertThat(row().getDeliveredAt()).isEqualTo(1100);
        assertThat(sent()).isOne();
    }

    @Test void unknownStopsAccountSkipsPendingAndLateReceiptCanResolveUnknown() {
        tx.executeWithoutResult(s -> sink.handleSendResultReported(result(false, "SEND_RESULT_UNKNOWN")));
        assertThat(row().getSendStatus()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT send_status FROM contact_friend_task_recipient WHERE id = 101", String.class)).isEqualTo("SKIPPED");
        assertThat(recipients.countUnfinished(1L)).isZero();
        tx.executeWithoutResult(s -> sink.handleAck(ack(7L, "msg", "DELIVERED")));
        assertThat(row().getSendStatus()).isEqualTo("SUCCESS");
        assertThat(sent()).isOne();
        assertThat(jdbc.queryForObject("SELECT state FROM contact_friend_task_account WHERE id = 10", String.class)).isEqualTo("FAILED");
    }

    @Test void differentTenantOrMessageCannotUpdateRecipient() {
        tx.executeWithoutResult(s -> sink.handleAck(ack(8L, "msg", "READ")));
        assertThat(row().getReadAt()).isNull();
        tx.executeWithoutResult(s -> sink.handleSendResultReported(result(true, null)));
        tx.executeWithoutResult(s -> sink.handleAck(ack(7L, "other-message", "READ")));
        assertThat(row().getReadAt()).isNull();
        assertThat(sent()).isOne();
    }

    @Test void accountWithAnInFlightMessageIsNotSelectedAgain() {
        assertThat(recipients.selectAccountIdsWithPending(1L, 10)).isEmpty();
        tx.executeWithoutResult(s -> sink.handleSendResultReported(result(true, null)));
        assertThat(recipients.selectAccountIdsWithPending(1L, 10)).containsExactly(10L);
        TenantContext.set(8L);
        assertThat(recipients.selectPage(1L, 10L, 0, 20)).isEmpty();
        assertThat(recipients.countByAccount(1L, 10L)).isZero();
    }

    @Test void recipientInsertUsesJidWithNullablePhoneAndIsIdempotent() {
        ContactFriendTaskRecipient row = new ContactFriendTaskRecipient();
        row.setTenantId(7L); row.setTaskId(1L); row.setTaskAccountId(10L);
        row.setContactJid("999999@lid"); row.setContactNamed(0); row.setCreatedAt(1L); row.setUpdatedAt(1L);
        tx.executeWithoutResult(s -> { recipients.insertBatch(java.util.List.of(row)); recipients.insertBatch(java.util.List.of(row)); });
        assertThat(recipients.countByAccount(1L, 10L)).isEqualTo(3);
    }

    @Configuration(proxyBeanMethods = false) @Import(MyBatisConfig.class)
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:contact_receipt;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            return source;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source, MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source); factory.setConfiguration(config); factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource("mapper/contact/ContactFriendTaskMapper.xml"),
                    new ClassPathResource("mapper/contact/ContactFriendTaskAccountMapper.xml"),
                    new ClassPathResource("mapper/contact/ContactFriendTaskRecipientMapper.xml"));
            return factory.getObject();
        }
        @Bean SqlSessionTemplate session(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
    }
}
