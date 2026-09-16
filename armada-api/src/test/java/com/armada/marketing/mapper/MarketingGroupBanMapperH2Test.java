package com.armada.marketing.mapper;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** 真正执行封禁 SQL，验证跨租户同 JID、未知健康值、删除与解除封禁。 */
class MarketingGroupBanMapperH2Test {
    private MarketingTaskMapper mapper;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:marketing_group_ban;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP ALL OBJECTS");
        // 复用测试专用 MySQL 函数适配，真实 Mapper 的 JOIN/CTE/租户插件保持不变。
        jdbc.execute("CREATE ALIAS SUBSTRING_INDEX FOR 'com.armada.testsupport.MysqlModeMapperInMemoryTest.substringIndex'");
        jdbc.execute("CREATE TABLE wa_group(id BIGINT PRIMARY KEY,tenant_id BIGINT,group_jid VARCHAR(128),deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE wa_group_profile(tenant_id BIGINT,group_id BIGINT,banned BOOLEAN)");
        jdbc.execute("INSERT INTO wa_group VALUES(1,7,'ban@g.us',NULL),(2,7,'ok@g.us',NULL),(3,8,'ok@g.us',NULL),(4,7,'unknown@g.us',NULL),(5,7,'deleted@g.us',1)");
        jdbc.execute("INSERT INTO wa_group_profile VALUES(7,1,TRUE),(7,2,FALSE),(8,3,TRUE),(7,4,NULL),(7,5,TRUE)");
        var cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        var production = new MyBatisConfig();
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(cfg);
        factory.setPlugins(production.mybatisPlusInterceptor(production.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/marketing/MarketingTaskMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(MarketingTaskMapper.class);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        TenantContext.set(7L);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void banSkipCannotRewriteSentOrAcceptedOrOtherTenantAttempts() {
        jdbc.execute("CREATE TABLE marketing_task_send_attempt(id BIGINT,tenant_id BIGINT,command_id VARCHAR(64),status INT,outbox_accepted_at BIGINT,reason_code VARCHAR(64),reason_message VARCHAR(128),group_status VARCHAR(32),group_status_reason VARCHAR(64),group_status_checked_at BIGINT,result_at BIGINT)");
        jdbc.execute("INSERT INTO marketing_task_send_attempt(id,tenant_id,command_id,status,outbox_accepted_at) VALUES(1,7,'one',0,NULL),(2,7,'two',1,NULL),(3,7,'three',0,100),(4,8,'four',0,NULL)");
        for (long id = 1; id <= 4; id++) {
            String command = List.of("one", "two", "three", "four").get((int) id - 1);
            var result = new com.armada.marketing.model.support.MarketingSendAttemptResult(
                    id, command, null, "GROUP_BANNED", "群组已封禁", "ban@g.us", null, null, null, 1000L);
            assertThat(mapper.markAttemptGroupBannedSkipped(result)).isEqualTo(id == 1 ? 1 : 0);
            assertThat(mapper.markAttemptGroupBannedSkipped(result)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM marketing_task_send_attempt WHERE id=1", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT group_status FROM marketing_task_send_attempt WHERE id=1", String.class)).isEqualTo("BANNED");
        assertThat(jdbc.queryForObject("SELECT status FROM marketing_task_send_attempt WHERE id=2", Integer.class)).isEqualTo(1);
    }

    @Test
    void detailReadsCurrentBanSeparatelyFromSuccessfulAttempt() {
        jdbc.execute("ALTER TABLE wa_group ADD display_name VARCHAR(128)");
        jdbc.execute("ALTER TABLE wa_group_profile ADD subject VARCHAR(128)");
        jdbc.execute("CREATE TABLE group_link(id BIGINT,tenant_id BIGINT,group_id BIGINT,group_name VARCHAR(128),link_url VARCHAR(128))");
        jdbc.execute("CREATE TABLE marketing_task_target(id BIGINT,tenant_id BIGINT,marketing_task_id BIGINT,account_id BIGINT,account_phone VARCHAR(32),target_scope INT,group_link_id BIGINT,group_jid VARCHAR(128),group_link_url VARCHAR(128),group_name VARCHAR(128))");
        jdbc.execute("CREATE TABLE marketing_task_send_attempt(id BIGINT,tenant_id BIGINT,marketing_task_id BIGINT,target_id BIGINT,status INT,reason_code VARCHAR(64),reason_message VARCHAR(128),group_status VARCHAR(32),group_status_reason VARCHAR(64),round_no BIGINT,attempt_no INT,result_at BIGINT,attempted_at BIGINT,created_at BIGINT,group_link_id BIGINT,group_jid VARCHAR(128),group_name VARCHAR(128))");
        jdbc.execute("CREATE TABLE wa_account_group_binding(tenant_id BIGINT,account_id BIGINT,group_id BIGINT,participant_id BIGINT)");
        jdbc.execute("CREATE TABLE wa_group_participant(id BIGINT,tenant_id BIGINT,group_id BIGINT,presence_status INT,last_exit_type VARCHAR(32))");
        jdbc.execute("INSERT INTO group_link VALUES(1,7,1,'blocked',NULL)");
        jdbc.execute("INSERT INTO marketing_task_target VALUES(10,7,42,31,'123',2,NULL,NULL,NULL,NULL)");
        jdbc.execute("INSERT INTO marketing_task_send_attempt VALUES(100,7,42,10,1,NULL,NULL,'NORMAL','GROUP_SEND_ALLOWED',1,1,1000,900,800,1,'ban@g.us','blocked')");
        jdbc.execute("INSERT INTO wa_account_group_binding VALUES(7,31,1,50)");
        jdbc.execute("INSERT INTO wa_group_participant VALUES(50,7,1,1,NULL)");
        // H2 对多层复用 CTE 的绑定参数存在限制；固定测试 taskId 后仍执行生产查询文本。
        try {
            var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ClassPathResource("mapper/marketing/MarketingTaskMapper.xml").getInputStream());
            var selects = document.getElementsByTagName("select");
            for (int i = 0; i < selects.getLength(); i++) {
                var element = (org.w3c.dom.Element) selects.item(i);
                if ("selectAccountGroupStatsByTaskId".equals(element.getAttribute("id"))) {
                    String sql = element.getTextContent().replace("#{taskId}", "42");
                    var interceptor = new com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor(
                            new MyBatisConfig().tenantLineHandler());
                    assertThat(jdbc.queryForList(interceptor.parserSingle(sql, null))).singleElement().satisfies(row -> {
                        assertThat(row.get("currentgroupbanned")).isEqualTo(true);
                        assertThat(row.get("groupstatus")).isEqualTo("NORMAL");
                        assertThat(((Number) row.get("latestexecutionstatus")).intValue()).isEqualTo(1);
                        assertThat(((Number) row.get("sentmessagecount")).intValue()).isEqualTo(1);
                        assertThat(((Number) row.get("membershipstatus")).intValue()).isEqualTo(1);
                    });
                    TenantContext.set(8L);
                    assertThat(jdbc.queryForList(interceptor.parserSingle(sql, null))).isEmpty();
                    return;
                }
            }
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        throw new AssertionError("生产 Mapper 缺少详情查询");
    }

    @Test
    void onlyCurrentTenantExplicitLiveBansAreBlockedAndRecoveryIsTransactional() {
        var jids = List.of("ban@g.us", "ok@g.us", "unknown@g.us", "deleted@g.us", "missing@g.us");
        assertThat(mapper.selectBannedGroupJids(jids)).containsExactly("ban@g.us");
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE wa_group_profile SET banned=FALSE WHERE tenant_id=7 AND group_id=1");
            assertThat(mapper.selectBannedGroupJids(jids)).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(mapper.selectBannedGroupJids(jids)).containsExactly("ban@g.us");
        TenantContext.set(8L);
        assertThat(mapper.selectBannedGroupJids(jids)).containsExactly("ok@g.us");
    }
}
