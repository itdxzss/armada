package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** WhatsApp 群最后一次完整成员快照 Mapper 的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(WhatsappGroupMemberSnapshotMapperDbTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class WhatsappGroupMemberSnapshotMapperDbTest {

    private static final long TENANT_ID = 7L;
    private static final long OTHER_TENANT_ID = 8L;
    private static final long GROUP_LINK_ID = 101L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private WhatsappGroupMemberSnapshotMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        resetSchema();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void insertBatchPersistsCompleteSnapshotAndOwnerIsAlwaysAdmin() {
        long snapshotAt = 1_000L;

        assertThat(mapper.insertBatch(List.of(
                member("8613800000000@s.whatsapp.net", "8613800000000", false, false, snapshotAt),
                member("51943333070@s.whatsapp.net", "51943333070", false, true, snapshotAt))))
                .isEqualTo(2);

        assertThat(mapper.selectByGroupLinkId(GROUP_LINK_ID))
                .extracting(WhatsappGroupMemberSnapshot::getParticipantJid)
                .containsExactly(
                        "51943333070@s.whatsapp.net",
                        "8613800000000@s.whatsapp.net");
        assertThat(mapper.selectByGroupLinkId(GROUP_LINK_ID).get(0))
                .satisfies(owner -> {
                    assertThat(owner.getIsOwner()).isTrue();
                    assertThat(owner.getIsAdmin()).isTrue();
                    assertThat(owner.getSnapshotAt()).isEqualTo(snapshotAt);
                });
    }

    @Test
    void participantIsUniqueWithinTenantAndGroup() {
        WhatsappGroupMemberSnapshot row = member(
                "8613800000000@s.whatsapp.net", "8613800000000", true, false, 1_000L);
        mapper.insertBatch(List.of(row));

        assertThatThrownBy(() -> mapper.insertBatch(List.of(row)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void deleteAndReadAreTenantScoped() {
        mapper.insertBatch(List.of(member(
                "8613800000000@s.whatsapp.net", "8613800000000", true, false, 1_000L)));

        TenantContext.set(OTHER_TENANT_ID);
        assertThat(mapper.selectByGroupLinkId(GROUP_LINK_ID)).isEmpty();
        assertThat(mapper.deleteByGroupLinkId(GROUP_LINK_ID)).isZero();
        assertThat(mapper.insertBatch(List.of(member(
                "8613800000000@s.whatsapp.net", "8613800000000", true, false, 2_000L))))
                .isEqualTo(1);

        TenantContext.set(TENANT_ID);
        assertThat(mapper.deleteByGroupLinkId(GROUP_LINK_ID)).isEqualTo(1);
        assertThat(mapper.selectByGroupLinkId(GROUP_LINK_ID)).isEmpty();
    }

    @Test
    void selectByGroupJidsUsesExplicitTenantAndGroupScope() {
        mapper.insertBatch(List.of(member(
                "8613800000000@s.whatsapp.net", "8613800000000", true, false, 1_000L)));
        TenantContext.set(OTHER_TENANT_ID);
        mapper.insertBatch(List.of(member(
                "51943333070@s.whatsapp.net", "51943333070", false, false, 2_000L)));
        TenantContext.set(TENANT_ID);

        assertThat(mapper.selectByGroupJids(TENANT_ID, List.of("120363-snapshot@g.us")))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getTenantId()).isEqualTo(TENANT_ID);
                    assertThat(row.getParticipantJid())
                            .isEqualTo("8613800000000@s.whatsapp.net");
                });
        assertThat(mapper.selectByGroupJids(OTHER_TENANT_ID, List.of("120363-snapshot@g.us")))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getTenantId()).isEqualTo(OTHER_TENANT_ID);
                    assertThat(row.getParticipantJid())
                            .isEqualTo("51943333070@s.whatsapp.net");
                });
        assertThat(mapper.selectByGroupJids(TENANT_ID, List.of("120363-other@g.us")))
                .isEmpty();
    }

    @Test
    void updateAdminRoleChangesOnlyCurrentTenantNonOwnerMembers() {
        mapper.insertBatch(List.of(
                member("owner@s.whatsapp.net", "100", true, true, 1_000L),
                member("member@s.whatsapp.net", "200", false, false, 1_000L)));

        assertThat(mapper.updateAdminRole(
                GROUP_LINK_ID,
                List.of("owner@s.whatsapp.net", "member@s.whatsapp.net"),
                true,
                2_000L)).isEqualTo(1);
        assertThat(mapper.selectByGroupLinkId(GROUP_LINK_ID))
                .filteredOn(row -> row.getParticipantJid().equals("member@s.whatsapp.net"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getRole()).isEqualTo("ADMIN");
                    assertThat(row.getIsAdmin()).isTrue();
                    assertThat(row.getUpdatedAt()).isEqualTo(2_000L);
                });

        TenantContext.set(OTHER_TENANT_ID);
        assertThat(mapper.updateAdminRole(
                GROUP_LINK_ID,
                List.of("member@s.whatsapp.net"),
                false,
                3_000L)).isZero();
    }

    private static WhatsappGroupMemberSnapshot member(
            String participantJid,
            String phone,
            boolean admin,
            boolean owner,
            long snapshotAt) {
        WhatsappGroupMemberSnapshot row = new WhatsappGroupMemberSnapshot();
        row.setGroupLinkId(GROUP_LINK_ID);
        row.setGroupJid("120363-snapshot@g.us");
        row.setParticipantJid(participantJid);
        row.setPhone(phone);
        row.setRole(owner ? "superadmin" : admin ? "admin" : null);
        row.setIsAdmin(admin);
        row.setIsOwner(owner);
        row.setSnapshotAt(snapshotAt);
        row.setCreatedAt(snapshotAt);
        row.setUpdatedAt(snapshotAt);
        return row;
    }

    private void resetSchema() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE whatsapp_group_member_snapshot (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_link_id BIGINT NOT NULL,
                    group_jid VARCHAR(128) NOT NULL,
                    participant_jid VARCHAR(128) NOT NULL,
                    phone VARCHAR(32),
                    role VARCHAR(32),
                    is_admin TINYINT NOT NULL DEFAULT 0,
                    is_owner TINYINT NOT NULL DEFAULT 0,
                    snapshot_at BIGINT NOT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    CONSTRAINT uq_whatsapp_group_member
                        UNIQUE (tenant_id, group_link_id, participant_jid)
                )
                """);
        execute("""
                CREATE INDEX idx_whatsapp_group_jid_snapshot
                ON whatsapp_group_member_snapshot
                    (tenant_id, group_jid, snapshot_at, group_link_id)
                """);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** 本测试所需的最小 MyBatis 与租户拦截器配置。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:whatsapp_group_member_snapshot_mapper_test;"
                    + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setUseGeneratedKeys(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource(
                    "mapper/group/WhatsappGroupMemberSnapshotMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        WhatsappGroupMemberSnapshotMapper whatsappGroupMemberSnapshotMapper(
                SqlSessionTemplate template) {
            return template.getMapper(WhatsappGroupMemberSnapshotMapper.class);
        }
    }
}
