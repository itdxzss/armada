package com.armada.group.mapper;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.service.GroupScriptCandidateService;
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

/** H2 执行真实 canonical 关系 SQL、租户插件和事务，不用 mock Mapper。 */
class GroupScriptCandidateMapperH2Test {
    JdbcTemplate jdbc;
    GroupScriptCandidateMapper mapper;
    GroupListCurrentMapper groupList;
    TransactionTemplate tx;
    @BeforeEach void setup() throws Exception {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:script_qualification;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds); jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE group_link (id BIGINT, tenant_id BIGINT, group_id BIGINT, deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE wa_account_group_binding (tenant_id BIGINT, account_id BIGINT, group_id BIGINT, participant_id BIGINT)");
        jdbc.execute("CREATE TABLE wa_group_participant (id BIGINT, tenant_id BIGINT, group_id BIGINT, presence_status INT, role INT)");
        jdbc.execute("CREATE TABLE wa_group_profile (tenant_id BIGINT, group_id BIGINT, announce_only INT, banned INT)");
        jdbc.execute("CREATE TABLE account (id BIGINT, tenant_id BIGINT, account_group_id BIGINT, deleted_at BIGINT, protocol_id VARCHAR(32), protocol_account_id VARCHAR(64), ws_phone VARCHAR(32))");
        jdbc.execute("CREATE TABLE account_state (tenant_id BIGINT, account_id BIGINT, login_state INT, account_state INT, risk_status INT, mute_status INT)");
        jdbc.execute("INSERT INTO group_link VALUES (40,7,400,NULL),(41,7,401,NULL),(42,8,402,NULL)");
        jdbc.execute("INSERT INTO wa_group_profile VALUES (7,400,0,0),(7,401,1,0),(8,402,0,0)");
        for (int i = 1; i <= 4; i++) {
            jdbc.update("INSERT INTO account VALUES (?,7,30,NULL,'WEB','account','15550000000')", i);
            jdbc.update("INSERT INTO account_state VALUES (7,?,1,2,NULL,NULL)", i);
            jdbc.update("INSERT INTO wa_account_group_binding VALUES (7,?,400,?)", i, i);
            jdbc.update("INSERT INTO wa_group_participant VALUES (?,7,400,1,1)", i);
        }
        jdbc.execute("INSERT INTO account VALUES (8,8,30,NULL,'WEB','other','16660000000')");
        jdbc.execute("INSERT INTO account_state VALUES (8,8,1,2,NULL,NULL)");
        jdbc.execute("INSERT INTO wa_account_group_binding VALUES (8,8,402,8)");
        jdbc.execute("INSERT INTO wa_group_participant VALUES (8,8,402,1,1)");
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        var production = new MyBatisConfig(); factory.setPlugins(production.mybatisPlusInterceptor(production.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/group/GroupScriptCandidateMapper.xml"),
                new ClassPathResource("mapper/group/GroupListCurrentMapper.xml"));
        var session = new SqlSessionTemplate(factory.getObject());
        mapper = session.getMapper(GroupScriptCandidateMapper.class); groupList = session.getMapper(GroupListCurrentMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds)); TenantContext.set(7L);
    }
    @AfterEach void cleanup() { TenantContext.clear(); }
    @Test void membershipPermissionAndAvailabilityComeFromRealRowsAndRollback() {
        tx.executeWithoutResult(status -> {
            jdbc.execute("UPDATE account_state SET login_state=2 WHERE account_id=2");
            jdbc.execute("UPDATE wa_group_participant SET presence_status=0 WHERE id=3");
            jdbc.execute("UPDATE account_state SET mute_status=1 WHERE account_id=4");
            var rows = mapper.list(List.of(40L), 30L, List.of());
            assertThat(rows).hasSize(4);
            assertThat(rows.get(0).messageSendAllowed()).isTrue();
            assertThat(rows.get(1).online()).isFalse();
            assertThat(rows.get(2).messageSendAllowed()).isNull();
            assertThat(rows.get(3).online()).isFalse();
            status.setRollbackOnly();
        });
        assertThat(mapper.list(List.of(40L), 30L, List.of())).allMatch(row -> row.online() && row.messageSendAllowed());
    }
    @Test void tenantAndPoolFiltersDoNotExpandAndEmptyServiceInputNeverQueriesAll() {
        assertThat(mapper.list(List.of(40L,42L), 30L, List.of())).hasSize(4);
        assertThat(mapper.list(List.of(40L), 99L, List.of())).isEmpty();
        assertThat(new GroupScriptCandidateService(mapper).list(List.of(), 30L, List.of())).isEmpty();
        TenantContext.set(8L); assertThat(mapper.list(List.of(40L,42L), 30L, List.of())).hasSize(1);
        TenantContext.clear(); assertThat(mapper.list(List.of(40L,42L), 30L, List.of())).isEmpty();
    }
    @Test void groupOptionCountUsesTheSameInGroupPoolFilterWithoutDuplicateGroups() {
        var query = new GroupLinkQuery(); query.setAccountGroupId(30L);
        assertThat(groupList.count(7L, query)).isEqualTo(1);
        jdbc.execute("UPDATE wa_group_participant SET presence_status=2 WHERE tenant_id=7");
        assertThat(groupList.count(7L, query)).isZero();
        assertThat(groupList.count(8L, query)).isEqualTo(1);
    }
    @Test void currentControlledAdminsAreCandidatesEvenOutsideThePusherPool() {
        jdbc.execute("UPDATE account SET account_group_id=99 WHERE id IN (1,2,3)");
        jdbc.execute("UPDATE wa_group_participant SET role=2 WHERE id=1");
        jdbc.execute("UPDATE wa_group_participant SET role=3 WHERE id=2");
        assertThat(mapper.list(List.of(40L), 30L, List.of()))
                .extracting(row -> row.accountId()).containsExactly(1L, 2L, 4L);
        assertThat(mapper.list(List.of(40L), 30L, List.of()))
                .extracting(row -> row.admin()).containsExactly(true, true, false);
        jdbc.execute("UPDATE wa_group_participant SET presence_status=2 WHERE id=1");
        assertThat(mapper.list(List.of(40L), 30L, List.of()))
                .extracting(row -> row.accountId()).containsExactly(2L, 4L);
        jdbc.execute("UPDATE wa_group_participant SET role=1 WHERE id=2");
        assertThat(mapper.list(List.of(40L), 30L, List.of(2L)))
                .extracting(row -> row.admin()).containsExactly(false, false);
        jdbc.execute("UPDATE account SET deleted_at=100 WHERE id=2");
        assertThat(mapper.list(List.of(40L), 30L, List.of(2L)))
                .extracting(row -> row.accountId()).containsExactly(4L);
    }
}
