package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.enums.GroupControlRelation;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 使用真实 Mapper、生产插件、Spring 事务和 H2 验证控制关系筛选。 */
class GroupControlRelationMapperH2Test {
    private JdbcTemplate jdbc;
    private GroupListCurrentMapper mapper;
    private TransactionTemplate tx;
    private SqlSessionFactory sessions;

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:group_control;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP ALL OBJECTS");
        schema();
        MyBatisConfig config = new MyBatisConfig();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(configuration);
        factory.setPlugins(config.mybatisPlusInterceptor(config.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/group/GroupListCurrentMapper.xml"));
        sessions = factory.getObject();
        mapper = new SqlSessionTemplate(sessions).getMapper(GroupListCurrentMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        TenantContext.set(7L);
        // 我方群主、我方普通管理员+外部 superadmin 但 creator 已退出、外部 creator、未知。
        group(1, "10001", true);
        member(1, "10001", 3, 1);
        account(1, "10001", 7, false);
        group(2, "10002", true);
        member(2, "20002", 2, 1);
        member(2, "30002", 3, 1);
        member(2, "10002", 3, 2);
        account(2, "20002", 7, false);
        group(3, "10003", true);
        member(3, "10003", 1, 1); // creator 即使没有 superadmin 标志也仍在群。
        member(3, "20003", 2, 1);
        account(3, "20003", 7, false);
        group(4, null, true);
        member(4, "20004", 2, 1);
        account(4, "20004", 7, false);
    }

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void inferredCreatorDoesNotProvePresenceOrChangeConfirmedMemberRoles() {
        jdbc.update("UPDATE group_link_preview SET creator_phone_source=1 WHERE group_link_id IN (1,3)");
        // 实际 superadmin 的权限保持；JID 推导不证明原创建者是否在群。
        assertThat(count(GroupControlRelation.CONTROLLED_OWNER)).isEqualTo(1);
        assertThat(count(GroupControlRelation.EXTERNAL_CREATOR_PRESENT)).isZero();
        assertThat(count(GroupControlRelation.UNKNOWN)).isEqualTo(3);
    }

    @Test
    void filtersCreatorAbsenceIndependentlyOfSuperadminAndKeepsUnknownSeparate() {
        assertThat(count(GroupControlRelation.CONTROLLED_OWNER)).isEqualTo(1);
        assertThat(count(GroupControlRelation.CONTROLLED_ADMIN_CREATOR_ABSENT)).isEqualTo(1);
        assertThat(count(GroupControlRelation.EXTERNAL_CREATOR_PRESENT)).isEqualTo(1);
        assertThat(count(GroupControlRelation.UNKNOWN)).isEqualTo(1);
    }

    @Test
    void selectionsAreOrOtherFiltersAreAndAndDuplicateAccountsDoNotDuplicateGroups() throws Exception {
        account(20, "20002", 7, false);
        GroupLinkQuery q = query(GroupControlRelation.CONTROLLED_OWNER,
                GroupControlRelation.CONTROLLED_ADMIN_CREATOR_ABSENT);
        assertThat(mapper.count(7L, q)).isEqualTo(2);
        q.setFolderId(2L);
        assertThat(mapper.count(7L, q)).isEqualTo(1);
        q.setFolderId(null);
        q.setAvailableAdmin(true);
        assertThat(mapper.count(7L, q)).isZero(); // 持有账号与在线可用性是独立条件。
        q.setAvailableAdmin(null);
        q.setPageSize(1);
        assertThat(pageIds(q)).containsExactly(2L);
        q.setPage(2);
        assertThat(pageIds(q)).containsExactly(1L);
    }

    @Test
    void missingSnapshotOrUnresolvedIdentityCannotProveCreatorAbsence() {
        jdbc.update("UPDATE wa_group_profile SET member_snapshot_at=NULL WHERE group_id=2");
        assertThat(count(GroupControlRelation.CONTROLLED_ADMIN_CREATOR_ABSENT)).isZero();
        assertThat(count(GroupControlRelation.UNKNOWN)).isEqualTo(2);
        jdbc.update("UPDATE wa_group_profile SET member_snapshot_at=100 WHERE group_id=2");
        member(2, null, 1, 1);
        assertThat(count(GroupControlRelation.CONTROLLED_ADMIN_CREATOR_ABSENT)).isZero();
        assertThat(count(GroupControlRelation.UNKNOWN)).isEqualTo(2);
    }

    @Test
    void crossTenantAndDeletedAccountsAreNotControlledAndEmptySelectionMeansAll() {
        jdbc.update("UPDATE account SET deleted_at=1 WHERE id=1");
        account(30, "10001", 8, false);
        account(31, "10003", 7, true);
        assertThat(count(GroupControlRelation.CONTROLLED_OWNER)).isZero();
        assertThat(count(GroupControlRelation.EXTERNAL_CREATOR_PRESENT)).isEqualTo(2);
        assertThat(mapper.count(7L, new GroupLinkQuery())).isEqualTo(4);
        assertThat(mapper.count(8L, query(GroupControlRelation.UNKNOWN))).isZero();
    }

    @Test
    void creatorJoiningAndLeavingChangesResultWithCurrentPresence() {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE wa_group_participant SET presence_status=1 WHERE group_id=2 AND phone='10002'");
            assertThat(count(GroupControlRelation.CONTROLLED_ADMIN_CREATOR_ABSENT)).isZero();
            assertThat(count(GroupControlRelation.EXTERNAL_CREATOR_PRESENT)).isEqualTo(2);
            status.setRollbackOnly();
        });
        assertThat(count(GroupControlRelation.CONTROLLED_ADMIN_CREATOR_ABSENT)).isEqualTo(1);
    }

    private long count(GroupControlRelation relation) { return mapper.count(7L, query(relation)); }

    /** H2 多次引用带参数 CTE 会丢失 LIMIT 参数；独立执行生产 page_ids 的原始 SQL。 */
    private List<Long> pageIds(GroupLinkQuery query) throws Exception {
        MappedStatement statement = sessions.getConfiguration().getMappedStatement(
                GroupListCurrentMapper.class.getName() + ".selectPage");
        Map<String, Object> parameters = Map.of("tenantId", 7L, "query", query);
        BoundSql original = statement.getBoundSql(parameters);
        String sql = original.getSql();
        String pageSql = sql.substring(sql.indexOf('(') + 1,
                sql.lastIndexOf("),", sql.indexOf("page_groups AS")));
        int bindings = (int) pageSql.chars().filter(c -> c == '?').count();
        BoundSql page = new BoundSql(sessions.getConfiguration(), pageSql,
                original.getParameterMappings().subList(0, bindings), parameters);
        original.getAdditionalParameters().forEach(page::setAdditionalParameter);
        List<Long> ids = new ArrayList<>();
        try (var connection = jdbc.getDataSource().getConnection();
             var prepared = connection.prepareStatement(pageSql)) {
            new DefaultParameterHandler(statement, parameters, page).setParameters(prepared);
            try (var rows = prepared.executeQuery()) {
                while (rows.next()) ids.add(rows.getLong("id"));
            }
        }
        return ids;
    }

    private GroupLinkQuery query(GroupControlRelation... relations) {
        GroupLinkQuery q = new GroupLinkQuery();
        q.setControlRelations(List.of(relations));
        return q;
    }

    private void group(long id, String creator, boolean snapshot) {
        jdbc.update("INSERT INTO group_link(id,tenant_id,group_id,created_at,link_url) VALUES(?,7,?,?,'url')", id, id, id);
        jdbc.update("INSERT INTO wa_group(id,tenant_id,folder_id) VALUES(?,7,?)", id, id);
        jdbc.update("INSERT INTO wa_group_profile(group_id,tenant_id,member_snapshot_at,member_snapshot_version) VALUES(?,7,?,?)", id, snapshot ? 100L : null, snapshot ? "v1" : null);
        jdbc.update("INSERT INTO group_link_preview(group_link_id,tenant_id,owner_phone) VALUES(?,7,?)", id, creator);
    }

    private void member(long group, String phone, int role, int presence) {
        jdbc.update("INSERT INTO wa_group_participant(tenant_id,group_id,phone,pn_jid,role,presence_status,last_snapshot_version) VALUES(7,?,?,?,?,?,'v1')", group, phone, phone == null ? null : phone + "@s.whatsapp.net", role, presence);
    }

    private void account(long id, String phone, long tenant, boolean deleted) {
        jdbc.update("INSERT INTO account(id,tenant_id,ws_phone,deleted_at) VALUES(?,?,?,?)", id, tenant, phone, deleted ? 1L : null);
    }

    private void schema() {
        jdbc.execute("CREATE TABLE group_link(id BIGINT PRIMARY KEY,tenant_id BIGINT,group_id BIGINT,group_invite_id BIGINT,created_at BIGINT,deleted_at BIGINT,folder_id BIGINT,label_id BIGINT,import_batch_id BIGINT,link_url VARCHAR,origin INT,membership_state INT,sync_protocol_mask INT)");
        jdbc.execute("CREATE TABLE wa_group(id BIGINT,tenant_id BIGINT,group_jid VARCHAR,folder_id BIGINT,display_name VARCHAR,remark VARCHAR,avatar_url VARCHAR,group_classification INT)");
        jdbc.execute("CREATE TABLE wa_group_profile(id BIGINT,tenant_id BIGINT,group_id BIGINT,member_snapshot_at BIGINT,member_snapshot_version VARCHAR,subject VARCHAR,member_count INT,checked_member_count INT,health_status INT,banned INT,metadata_observed_at BIGINT,last_checked_at BIGINT,last_error_code VARCHAR,wa_created_at BIGINT,current_invite_id BIGINT)");
        jdbc.execute("CREATE TABLE wa_group_participant(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,group_id BIGINT,phone VARCHAR,pn_jid VARCHAR,lid_jid VARCHAR,role INT,presence_status INT,last_snapshot_version VARCHAR)");
        jdbc.execute("CREATE TABLE group_link_preview(creator_phone_source TINYINT NOT NULL DEFAULT 2, tenant_id BIGINT,group_link_id BIGINT,owner_phone VARCHAR,creator_country_iso2 VARCHAR,creator_continent_code VARCHAR)");
        jdbc.execute("CREATE TABLE account(id BIGINT,tenant_id BIGINT,ws_phone VARCHAR,protocol_account_id VARCHAR,deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE account_state(tenant_id BIGINT,account_id BIGINT,login_state INT,account_state INT)");
        jdbc.execute("CREATE TABLE wa_group_invite(id BIGINT,tenant_id BIGINT,invite_code VARCHAR,display_name VARCHAR,preview_subject VARCHAR,remark VARCHAR,avatar_url VARCHAR,health_status INT,banned INT,checked_member_count INT,preview_observed_at BIGINT,last_checked_at BIGINT,last_error_code VARCHAR)");
        jdbc.execute("CREATE TABLE group_link_import_batch(id BIGINT,tenant_id BIGINT,source_file_name VARCHAR)");
        jdbc.execute("CREATE TABLE country(iso2 VARCHAR,name_zh VARCHAR,flag VARCHAR,continent_code VARCHAR,deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE group_folder(id BIGINT,tenant_id BIGINT,name VARCHAR,deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE group_metadata_sync_task(tenant_id BIGINT,group_link_id BIGINT,status VARCHAR,last_success_at BIGINT,last_error_message VARCHAR)");
    }
}
