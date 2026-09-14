package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 用真实详情 SQL、生产租户插件和事务验证字段上报后可以读取完整成员快照。 */
class GroupDetailCurrentMapperH2Test {

    private static final long TENANT_ID = 7L;
    private static final long GROUP_LINK_ID = 10L;
    private JdbcTemplate jdbc;
    private GroupListCurrentMapper mapper;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:group_detail_current;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        createSchema();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        MyBatisConfig production = new MyBatisConfig();
        factory.setPlugins(production.mybatisPlusInterceptor(production.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/group/GroupListCurrentMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(GroupListCurrentMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TenantContext.set(TENANT_ID);
        jdbc.update("INSERT INTO group_link VALUES (10,7,101,NULL),(20,8,201,NULL)");
        jdbc.update("INSERT INTO wa_group VALUES (101,7,'reported@g.us','avatar.jpg'),"
                + "(201,8,'other@g.us',NULL)");
        jdbc.update("""
                INSERT INTO wa_group_profile
                  (id,tenant_id,group_id,subject,member_count,announce_only,admin_only_edit_info,
                   member_add_mode,member_snapshot_at,member_snapshot_version)
                VALUES (1,7,101,'已上报群名',15,FALSE,FALSE,FALSE,200,'report-v1'),
                       (2,8,201,'其他租户群',1,TRUE,TRUE,TRUE,300,'other-v1')
                """);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void fieldReportedProfileExposesSnapshotWithoutAdvancingLegacyMarker() {
        for (int index = 0; index < 15; index++) {
            insertMember(index + 1, TENANT_ID, 101, index == 0 ? 3 : index < 4 ? 2 : 1,
                    "report-v1", 1);
        }
        insertMember(100, TENANT_ID, 101, 1, "older-snapshot", 1);
        insertMember(101, TENANT_ID, 101, 1, "report-v1", 2);
        insertMember(102, 8, 101, 3, "report-v1", 1);
        insertMember(103, TENANT_ID, 201, 3, "report-v1", 1);

        tx.executeWithoutResult(status -> {
            var profile = mapper.selectGroupDetail(TENANT_ID, GROUP_LINK_ID);
            assertThat(profile.getWaSubject()).isEqualTo("已上报群名");
            assertThat(profile.getAnnounceOnly()).isFalse();
            assertThat(profile.getMemberLinkMode()).isNull();
            assertThat(profile.getMetadataObservedAt()).isNull();
            assertThat(profile.getMemberSnapshotVersion()).isEqualTo("report-v1");
            var members = mapper.selectGroupDetailMembers(TENANT_ID, GROUP_LINK_ID);
            assertThat(members).hasSize(15);
            assertThat(members).filteredOn(WhatsappGroupMemberSnapshot::getIsAdmin, true).hasSize(4);
            assertThat(members.get(0).getIsOwner()).isTrue();
            assertThat(members).extracting(WhatsappGroupMemberSnapshot::getSnapshotAt).containsOnly(200L);
        });
        assertThat(jdbc.queryForObject("SELECT metadata_observed_at FROM wa_group_profile WHERE id=1",
                Long.class)).isNull();
    }

    @Test
    void missingSnapshotAndCompleteEmptySnapshotRemainDistinguishable() {
        assertThat(mapper.selectGroupDetail(TENANT_ID, GROUP_LINK_ID).getMemberSnapshotVersion())
                .isEqualTo("report-v1");
        assertThat(mapper.selectGroupDetailMembers(TENANT_ID, GROUP_LINK_ID)).isEmpty();
        jdbc.update("UPDATE wa_group_profile SET member_snapshot_version=NULL,"
                + "metadata_observed_at=200 WHERE id=1");
        insertMember(1, TENANT_ID, 101, 3, "report-v1", 1);

        assertThat(mapper.selectGroupDetail(TENANT_ID, GROUP_LINK_ID).getMemberSnapshotVersion()).isNull();
        assertThat(mapper.selectGroupDetailMembers(TENANT_ID, GROUP_LINK_ID)).isEmpty();
    }

    @Test
    void explicitTenantAndDeletedHandleFiltersStillApply() {
        TenantContext.set(8L);
        assertThat(mapper.selectGroupDetail(TENANT_ID, 20L)).isNull();
        assertThat(mapper.selectGroupDetailMembers(TENANT_ID, 20L)).isEmpty();
        assertThat(mapper.selectGroupDetail(8L, GROUP_LINK_ID)).isNull();
        assertThat(mapper.selectGroupDetail(TENANT_ID, GROUP_LINK_ID).getWaSubject())
                .isEqualTo("已上报群名");
        jdbc.update("UPDATE group_link SET deleted_at=500 WHERE id=10");
        assertThat(mapper.selectGroupDetail(TENANT_ID, GROUP_LINK_ID)).isNull();
        assertThat(mapper.selectGroupDetailMembers(TENANT_ID, GROUP_LINK_ID)).isEmpty();
    }

    private void insertMember(long id, long tenantId, long groupId, int role, String version, int presence) {
        jdbc.update("INSERT INTO wa_group_participant "
                + "(id,tenant_id,group_id,pn_jid,phone,role,last_snapshot_version,presence_status) "
                + "VALUES (?,?,?,?,?,?,?,?)", id, tenantId, groupId, "1555000" + id + "@s.whatsapp.net",
                "1555000" + id, role, version, presence);
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE group_link (id BIGINT PRIMARY KEY, tenant_id BIGINT,"
                + "group_id BIGINT, deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE wa_group (id BIGINT PRIMARY KEY, tenant_id BIGINT,"
                + "group_jid VARCHAR(100), avatar_url VARCHAR(255))");
        jdbc.execute("""
                CREATE TABLE wa_group_profile (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT, group_id BIGINT,
                  subject VARCHAR(100), description VARCHAR(255), member_count INT,
                  announce_only BOOLEAN, admin_only_edit_info BOOLEAN, member_add_mode BOOLEAN,
                  member_link_mode BOOLEAN, join_approval_mode BOOLEAN, ephemeral_duration_seconds INT,
                  wa_created_at BIGINT, metadata_observed_at BIGINT, member_snapshot_at BIGINT,
                  member_snapshot_version VARCHAR(100), created_at BIGINT, updated_at BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE wa_group_participant (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT, group_id BIGINT,
                  pn_jid VARCHAR(100), lid_jid VARCHAR(100), phone VARCHAR(50), role INT,
                  last_snapshot_version VARCHAR(100), presence_status INT,
                  created_at BIGINT, updated_at BIGINT)
                """);
    }
}
