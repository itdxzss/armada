package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.mapper.GroupMetadataPatchMapper;
import com.armada.group.model.dto.GroupMetadataPatch;
import com.armada.group.model.dto.GroupMetadataPatchField;
import com.armada.group.model.enums.GroupMetadataFieldSource;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.EnumSet;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 在真实 MySQL 上锁定群资料字段级 patch 的逐字段版本语义。
 *
 * <p>核心是不同字段互不阻挡：整行水位会让"稍早但仍应补齐的字段"被新事件挡住，这正是引入
 * V127 逐字段版本列要消除的缺陷。</p>
 */
@Testcontainers
class GroupMetadataPatchServiceMySqlTest {

    private static final long TENANT_ID = 7L;
    private static final String GROUP_JID = "120363-patch-001@g.us";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("armada_group_patch")
            .withUsername("armada")
            .withPassword("armada")
            .withCommand("--transaction-isolation=REPEATABLE-READ");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactionTemplate;
    private static GroupMetadataPatchServiceImpl service;

    @BeforeAll
    static void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        GroupCurrentSnapshotMySqlTestSupport.createLegacyContextSchema(jdbc);
        GroupCurrentSnapshotMySqlTestSupport.executeV120(dataSource);
        GroupCurrentSnapshotMySqlTestSupport.executeV121(dataSource);
        GroupCurrentSnapshotMySqlTestSupport.executeV127(dataSource);

        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        MyBatisConfig myBatisConfig = new MyBatisConfig();
        MybatisPlusInterceptor interceptor =
                myBatisConfig.mybatisPlusInterceptor(myBatisConfig.tenantLineHandler());
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setUseGeneratedKeys(true);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setPlugins(interceptor);
        factoryBean.setMapperLocations(
                new ClassPathResource("mapper/group/GroupMetadataPatchMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) {
            throw new IllegalStateException("无法创建群资料 patch 测试 SqlSessionFactory");
        }
        service = new GroupMetadataPatchServiceImpl(
                new SqlSessionTemplate(factory).getMapper(GroupMetadataPatchMapper.class));
    }

    @AfterAll
    static void clearTenant() {
        TenantContext.clear();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM wa_group_profile");
        jdbc.update("DELETE FROM wa_group");
    }

    @Test
    void firstPatchCreatesMinimalGroupAndWritesObservedFields() {
        apply(patch(EnumSet.of(GroupMetadataPatchField.SUBJECT), "Alpha", null, null,
                GroupMetadataFieldSource.METADATA_EVENT, 1_000L));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wa_group WHERE group_jid = ?", Integer.class, GROUP_JID))
                .as("群未建档时按 groupJid 创建最小身份")
                .isEqualTo(1);
        assertThat(text("subject")).isEqualTo("Alpha");
        assertThat(text("subject_source")).isEqualTo("METADATA_EVENT");
        assertThat(number("subject_observed_at")).isEqualTo(1_000L);
        assertThat(text("description")).as("未进 mask 的字段不写入").isNull();
        assertThat(number("description_observed_at")).isNull();
        assertThat(number("metadata_observed_at"))
                .as("字段级 patch 不得推进整行水位，否则后到的账号快照会被误判为旧")
                .isNull();
    }

    @Test
    void differentFieldsDoNotBlockEachOtherWhenOutOfOrder() {
        // 描述在 t2 被观察到并先落库；群名在 t1 被观察到但后到达。
        apply(patch(EnumSet.of(GroupMetadataPatchField.DESCRIPTION), null, "Later desc", null,
                GroupMetadataFieldSource.METADATA_EVENT, 2_000L));
        apply(patch(EnumSet.of(GroupMetadataPatchField.SUBJECT), "Earlier name", null, null,
                GroupMetadataFieldSource.METADATA_EVENT, 1_000L));

        assertThat(text("description")).isEqualTo("Later desc");
        assertThat(text("subject"))
                .as("整行水位会把这条稍早的群名事件整体判旧丢弃，逐字段版本必须让它写入")
                .isEqualTo("Earlier name");
        assertThat(number("subject_observed_at")).isEqualTo(1_000L);
        assertThat(number("description_observed_at")).isEqualTo(2_000L);
    }

    @Test
    void olderEventCannotOverwriteNewerFieldValue() {
        apply(patch(EnumSet.of(GroupMetadataPatchField.SUBJECT), "Newer", null, null,
                GroupMetadataFieldSource.METADATA_EVENT, 3_000L));
        apply(patch(EnumSet.of(GroupMetadataPatchField.SUBJECT), "Older", null, null,
                GroupMetadataFieldSource.METADATA_EVENT, 2_000L));

        assertThat(text("subject")).isEqualTo("Newer");
        assertThat(number("subject_observed_at")).isEqualTo(3_000L);
    }

    @Test
    void metadataEventWinsOverSnapshotAtSameObservedAt() {
        apply(patch(EnumSet.of(GroupMetadataPatchField.SUBJECT), "From snapshot", null, null,
                GroupMetadataFieldSource.GROUP_SNAPSHOT, 5_000L));
        apply(patch(EnumSet.of(GroupMetadataPatchField.SUBJECT), "From event", null, null,
                GroupMetadataFieldSource.METADATA_EVENT, 5_000L));

        assertThat(text("subject"))
                .as("同一事实时间时精确事件优先于完整快照")
                .isEqualTo("From event");

        // 反向：快照不得在同一时间压过已写入的精确事件。
        apply(patch(EnumSet.of(GroupMetadataPatchField.SUBJECT), "Snapshot again", null, null,
                GroupMetadataFieldSource.GROUP_SNAPSHOT, 5_000L));
        assertThat(text("subject")).isEqualTo("From event");
    }

    @Test
    void unmaskedFieldsKeepStoredValueAndVersion() {
        apply(patch(EnumSet.of(GroupMetadataPatchField.SUBJECT, GroupMetadataPatchField.DESCRIPTION),
                "Keep me", "Keep desc", null, GroupMetadataFieldSource.METADATA_EVENT, 1_000L));
        // 后续事件只带描述，群名不在 mask 内，即使事实时间更新也不得改动群名。
        apply(patch(EnumSet.of(GroupMetadataPatchField.DESCRIPTION), "Ignored name", "New desc",
                null, GroupMetadataFieldSource.METADATA_EVENT, 9_000L));

        assertThat(text("subject")).isEqualTo("Keep me");
        assertThat(number("subject_observed_at")).isEqualTo(1_000L);
        assertThat(text("description")).isEqualTo("New desc");
        assertThat(number("description_observed_at")).isEqualTo(9_000L);
    }

    @Test
    void explicitFalseZeroAndEmptyDescriptionArePersisted() {
        Set<GroupMetadataPatchField> mask = EnumSet.of(
                GroupMetadataPatchField.DESCRIPTION,
                GroupMetadataPatchField.ANNOUNCE_ONLY,
                GroupMetadataPatchField.EPHEMERAL_DURATION_SECONDS);
        apply(new GroupMetadataPatch(
                TENANT_ID, GROUP_JID, mask, null, "", true, null, null, null, 86_400,
                GroupMetadataFieldSource.METADATA_EVENT, 1_000L, "evt-1"));

        assertThat(text("description")).as("明确观察到的空描述必须落库").isEmpty();
        assertThat(number("announce_only")).isEqualTo(1L);
        assertThat(number("ephemeral_duration_seconds")).isEqualTo(86_400L);

        apply(new GroupMetadataPatch(
                TENANT_ID, GROUP_JID, mask, null, "", false, null, null, null, 0,
                GroupMetadataFieldSource.METADATA_EVENT, 2_000L, "evt-2"));

        assertThat(number("announce_only")).as("明确 false 必须落库").isZero();
        assertThat(number("ephemeral_duration_seconds")).as("0 表示明确关闭限时消息").isZero();
    }

    private static void apply(GroupMetadataPatch patch) {
        TenantContext.set(TENANT_ID);
        try {
            transactionTemplate.executeWithoutResult(status -> service.applyPatch(patch));
        } finally {
            TenantContext.clear();
        }
    }

    private static GroupMetadataPatch patch(
            Set<GroupMetadataPatchField> mask,
            String subject,
            String description,
            Boolean announceOnly,
            GroupMetadataFieldSource source,
            long observedAt) {
        return new GroupMetadataPatch(
                TENANT_ID, GROUP_JID, mask, subject, description, announceOnly,
                null, null, null, null, source, observedAt, "evt-" + observedAt);
    }

    private static String text(String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM wa_group_profile profile "
                        + "JOIN wa_group g ON g.id = profile.group_id WHERE g.group_jid = ?",
                String.class, GROUP_JID);
    }

    private static Long number(String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM wa_group_profile profile "
                        + "JOIN wa_group g ON g.id = profile.group_id WHERE g.group_jid = ?",
                Long.class, GROUP_JID);
    }
}
