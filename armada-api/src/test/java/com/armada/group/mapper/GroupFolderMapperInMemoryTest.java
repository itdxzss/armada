package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.entity.GroupFolder;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.group.model.vo.GroupFolderVO;
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.security.DataScope;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 群组运营分组 Mapper 的 H2 MySQL 模式与租户隔离测试。 */
@SpringJUnitConfig(GroupFolderMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class GroupFolderMapperInMemoryTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GroupFolderMapper mapper;

    private DataScope scope;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        scope = DataScope.self(501L);
        resetSchema();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void listAndUsableLinksShareTheAvailableUnbannedRule() throws SQLException {
        GroupFolder folder = folder("印度组", 100L);
        mapper.insert(folder);
        insertLink(1L, 7L, folder.getId(), "chat.whatsapp.com/AVAILABLE", null);
        insertHealth(1L, 7L, 1L, 1, 0);
        insertLink(2L, 7L, folder.getId(), "chat.whatsapp.com/BANNED", null);
        insertHealth(2L, 7L, 2L, 1, 1);
        insertLink(3L, 7L, folder.getId(), "chat.whatsapp.com/INVALID", null);
        insertHealth(3L, 7L, 3L, 2, 0);
        insertLink(4L, 7L, folder.getId(), "chat.whatsapp.com/UNCHECKED", null);
        insertLink(5L, 7L, folder.getId(), "chat.whatsapp.com/DELETED", 900L);
        insertHealth(5L, 7L, 5L, 1, 0);

        GroupFolderQuery query = query();
        List<GroupFolderVO> rows = mapper.selectPage(query);

        assertThat(mapper.countPage(query)).isEqualTo(1);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("印度组");
            assertThat(row.groupCount()).isEqualTo(1);
        });
        assertThat(mapper.selectUsableLinks(folder.getId(), 501L))
                .containsExactly("chat.whatsapp.com/AVAILABLE");
    }

    @Test
    void folderQueriesUseCurrentFactsInsteadOfStaleLegacyHealth() throws SQLException {
        GroupFolder folder = folder("新模型健康", 100L);
        mapper.insert(folder);
        insertLink(1L, 7L, folder.getId(), "chat.whatsapp.com/CURRENT", null);
        insertHealth(1L, 7L, 1L, 1, 0);
        execute("UPDATE group_link_health SET health_status = 2 WHERE group_link_id = 1");

        GroupFolderQuery query = query();

        assertThat(mapper.selectPage(query)).singleElement()
                .satisfies(row -> assertThat(row.groupCount()).isEqualTo(1));
        assertThat(mapper.selectUsableLinks(folder.getId(), 501L))
                .containsExactly("chat.whatsapp.com/CURRENT");
    }

    @Test
    void usableLinksConvertInternalGroupEntryToInviteLink() throws SQLException {
        GroupFolder folder = folder("内部群", 100L);
        mapper.insert(folder);
        insertLink(1L, 7L, folder.getId(), "wa://group/120363001@g.us", null);
        insertPreview(1L, 7L, 1L, "AbCdEfGhIjKlMnOpQrStUv");
        insertHealth(1L, 7L, 1L, 1, 0);

        GroupFolderQuery query = query();

        assertThat(mapper.selectUsableLinks(folder.getId(), 501L))
                .containsExactly("chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv");
        assertThat(mapper.selectPage(query)).singleElement()
                .satisfies(row -> assertThat(row.groupCount()).isEqualTo(1));
    }

    @Test
    void usableResourcesExposeCurrentGroupIdentityForRuntimeClaim() throws SQLException {
        GroupFolder folder = folder("任务资源池", 100L);
        mapper.insert(folder);
        insertLink(1L, 7L, folder.getId(), "wa://group/120363001@g.us", null);
        insertPreview(1L, 7L, 1L, "CurrentInviteCode0001");
        insertHealth(1L, 7L, 1L, 1, 0);

        assertThat(mapper.selectUsableResources(folder.getId(), 501L)).singleElement()
                .satisfies(resource -> {
                    assertThat(resource.groupLinkId()).isEqualTo(1L);
                    assertThat(resource.groupJid()).isEqualTo("120363001@g.us");
                    assertThat(resource.normalizedLink())
                            .isEqualTo("chat.whatsapp.com/CurrentInviteCode0001");
                    assertThat(resource.inviteCode()).isEqualTo("CurrentInviteCode0001");
                });
        assertThat(mapper.selectUsableResourceForUpdate(folder.getId(), 1L, 501L))
                .isEqualTo(mapper.selectUsableResources(folder.getId(), 501L).get(0));
    }

    @Test
    void usableLinksPreferObservedCurrentInviteForImportedEntry() throws SQLException {
        GroupFolder folder = folder("轮换链接群", 100L);
        mapper.insert(folder);
        insertLink(1L, 7L, folder.getId(),
                "chat.whatsapp.com/OriginalInviteCode001", null);
        insertPreview(1L, 7L, 1L, "CurrentInviteCode0002");
        insertHealth(1L, 7L, 1L, 1, 0);

        assertThat(mapper.selectUsableLinks(folder.getId(), 501L))
                .containsExactly("chat.whatsapp.com/CurrentInviteCode0002");
    }

    @Test
    void usableLinksExcludeInternalGroupEntryWithoutInviteCode() throws SQLException {
        GroupFolder folder = folder("缺少邀请码", 100L);
        mapper.insert(folder);
        insertLink(1L, 7L, folder.getId(), "wa://group/120363002@g.us", null);
        insertPreview(1L, 7L, 1L, null);
        insertHealth(1L, 7L, 1L, 1, 0);

        GroupFolderQuery query = query();

        assertThat(mapper.selectUsableLinks(folder.getId(), 501L)).isEmpty();
        assertThat(mapper.selectPage(query)).singleElement()
                .satisfies(row -> assertThat(row.groupCount()).isZero());
    }

    @Test
    void softDeletingFolderDoesNotDeleteGroupEntries() throws SQLException {
        GroupFolder folder = folder("待删除", 100L);
        mapper.insert(folder);
        insertLink(1L, 7L, folder.getId(), "chat.whatsapp.com/ONE", null);
        insertLink(2L, 7L, folder.getId(), "chat.whatsapp.com/TWO", null);

        assertThat(mapper.softDeleteByIds(List.of(folder.getId()), scope, 500L)).isEqualTo(1);

        assertThat(count("SELECT COUNT(*) FROM group_link WHERE deleted_at IS NULL"))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM group_link WHERE folder_id = " + folder.getId()))
                .isEqualTo(2);
        assertThat(mapper.selectById(folder.getId(), scope)).isNull();
    }

    @Test
    void otherTenantCannotReadOrMutateFolder() throws SQLException {
        GroupFolder folder = folder("租户七", 100L);
        mapper.insert(folder);
        insertLink(1L, 7L, folder.getId(), "chat.whatsapp.com/ONE", null);
        insertHealth(1L, 7L, 1L, 1, 0);

        TenantContext.set(8L);
        assertThat(mapper.selectById(folder.getId(), scope)).isNull();
        assertThat(mapper.selectUsableLinks(folder.getId(), 501L)).isEmpty();
        assertThat(mapper.softDeleteByIds(List.of(folder.getId()), scope, 500L)).isZero();
    }

    @Test
    void taskFolderOptionsExcludeSystemUsedFolder() throws SQLException {
        GroupFolder custom = folder("今日待拉群", 100L);
        mapper.insert(custom);
        execute("INSERT INTO group_folder "
                + "(tenant_id, owner_user_id, name, system_builtin, created_at, updated_at) "
                + "VALUES (7, 501, '已使用群组', 1, 100, 100)");

        assertThat(mapper.selectOptions(scope))
                .containsExactly(new com.armada.group.model.vo.GroupFolderOptionVO(
                        custom.getId(), 501L, "今日待拉群"));
    }

    @Test
    void userScopeSeesOnlyOwnFoldersWhileAdminSeesOwnedAndHistoricalRows()
            throws SQLException {
        execute("INSERT INTO group_folder "
                + "(id, tenant_id, owner_user_id, name, created_at, updated_at) VALUES "
                + "(101, 7, 501, '同名组', 100, 100), "
                + "(102, 7, 502, '同名组', 101, 101), "
                + "(103, 7, NULL, '历史组', 102, 102), "
                + "(201, 8, 501, '跨租户组', 103, 103)");

        GroupFolderQuery selfQuery = query();
        assertThat(mapper.countPage(selfQuery)).isEqualTo(1);
        assertThat(mapper.selectPage(selfQuery))
                .extracting(GroupFolderVO::id)
                .containsExactly(101L);
        assertThat(mapper.selectOptions(scope))
                .extracting(GroupFolderOptionVO::id)
                .containsExactly(101L);

        GroupFolderQuery adminQuery = new GroupFolderQuery();
        adminQuery.applyDataScope(DataScope.all(9001L));
        assertThat(mapper.countPage(adminQuery)).isEqualTo(3);
        assertThat(mapper.selectPage(adminQuery))
                .extracting(GroupFolderVO::id)
                .containsExactly(103L, 102L, 101L);
    }

    @Test
    void missingOrSystemScopeFailsClosed() {
        GroupFolderQuery missingScope = new GroupFolderQuery();
        assertThat(mapper.countPage(missingScope)).isZero();
        assertThat(mapper.selectPage(missingScope)).isEmpty();
        assertThat(mapper.selectOptions(DataScope.system("folder-job"))).isEmpty();
        assertThat(mapper.selectById(1L, null)).isNull();
    }

    private GroupFolder folder(String name, long now) {
        GroupFolder row = new GroupFolder();
        row.setName(name);
        row.setOwnerUserId(501L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setCreatedBy(501L);
        return row;
    }

    private void resetSchema() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE group_folder (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    owner_user_id BIGINT,
                    name VARCHAR(100) NOT NULL,
                    system_builtin TINYINT NOT NULL DEFAULT 0,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    created_by BIGINT,
                    deleted_at BIGINT,
                    CONSTRAINT uq_group_folder_name UNIQUE (tenant_id, owner_user_id, name)
                )
                """);
        execute("""
                CREATE TABLE group_link (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    owner_user_id BIGINT,
                    group_id BIGINT,
                    group_invite_id BIGINT,
                    link_url VARCHAR(255) NOT NULL,
                    folder_id BIGINT,
                    updated_at BIGINT NOT NULL,
                    deleted_at BIGINT
                )
                """);
        execute("""
                CREATE TABLE wa_group (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_jid VARCHAR(128),
                    folder_id BIGINT,
                    deleted_at BIGINT
                )
                """);
        execute("""
                CREATE TABLE wa_group_profile (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_id BIGINT NOT NULL,
                    current_invite_id BIGINT,
                    health_status TINYINT,
                    banned TINYINT,
                    CONSTRAINT uq_test_group_profile UNIQUE (tenant_id, group_id)
                )
                """);
        execute("""
                CREATE TABLE wa_group_invite (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    invite_code VARCHAR(128) NOT NULL,
                    health_status TINYINT,
                    banned TINYINT,
                    deleted_at BIGINT
                )
                """);
        execute("""
                CREATE TABLE group_link_health (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_link_id BIGINT NOT NULL,
                    health_status TINYINT,
                    is_banned TINYINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """);
        execute("""
                CREATE TABLE group_link_preview (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_link_id BIGINT NOT NULL,
                    invite_code VARCHAR(64),
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    CONSTRAINT uq_group_link_preview_link UNIQUE (tenant_id, group_link_id)
                )
                """);
    }

    private void insertLink(long id, long tenantId, long folderId,
                            String link, Long deletedAt) throws SQLException {
        boolean internalGroup = link.startsWith("wa://group/");
        Long groupId = internalGroup ? id : null;
        Long inviteId = internalGroup ? null : id;
        execute("INSERT INTO group_link "
                + "(id, tenant_id, owner_user_id, group_id, group_invite_id, link_url, folder_id, updated_at, "
                + "deleted_at) VALUES ("
                + id + ", " + tenantId + ", 501, " + sqlLong(groupId) + ", " + sqlLong(inviteId)
                + ", '" + link + "', " + folderId + ", 100, "
                + (deletedAt == null ? "NULL" : deletedAt) + ")");
        if (internalGroup) {
            execute("INSERT INTO wa_group (id, tenant_id, group_jid, folder_id, deleted_at) VALUES ("
                    + id + ", " + tenantId + ", '" + link.substring("wa://group/".length())
                    + "', " + folderId + ", "
                    + sqlLong(deletedAt) + ")");
            execute("INSERT INTO wa_group_profile (tenant_id, group_id) VALUES ("
                    + tenantId + ", " + id + ")");
        } else {
            String inviteCode = link.substring(link.lastIndexOf('/') + 1);
            execute("INSERT INTO wa_group_invite "
                    + "(id, tenant_id, invite_code, deleted_at) VALUES ("
                    + id + ", " + tenantId + ", '" + inviteCode + "', "
                    + sqlLong(deletedAt) + ")");
        }
    }

    private void insertHealth(long id, long tenantId, long groupLinkId,
                              int status, int banned) throws SQLException {
        execute("INSERT INTO group_link_health "
                + "(id, tenant_id, group_link_id, health_status, is_banned, created_at, updated_at) "
                + "VALUES (" + id + ", " + tenantId + ", " + groupLinkId + ", "
                + status + ", " + banned + ", 100, 100)");
        execute("UPDATE wa_group_profile SET health_status = " + status + ", banned = " + banned
                + " WHERE tenant_id = " + tenantId + " AND group_id = " + groupLinkId);
        execute("UPDATE wa_group_invite SET health_status = " + status + ", banned = " + banned
                + " WHERE tenant_id = " + tenantId + " AND id = " + groupLinkId);
    }

    private void insertPreview(long id, long tenantId, long groupLinkId,
                               String inviteCode) throws SQLException {
        String storedInviteCode = inviteCode == null ? "NULL" : "'" + inviteCode + "'";
        execute("INSERT INTO group_link_preview "
                + "(id, tenant_id, group_link_id, invite_code, created_at, updated_at) "
                + "VALUES (" + id + ", " + tenantId + ", " + groupLinkId + ", "
                + storedInviteCode + ", 100, 100)");
        if (inviteCode != null) {
            execute("MERGE INTO wa_group_invite "
                    + "(id, tenant_id, invite_code, health_status, banned, deleted_at) KEY(id) "
                    + "VALUES (" + id + ", " + tenantId + ", '" + inviteCode
                    + "', NULL, NULL, NULL)");
            execute("UPDATE group_link SET group_invite_id = " + id + " WHERE id = " + groupLinkId);
            execute("UPDATE wa_group_profile SET current_invite_id = " + id
                    + " WHERE tenant_id = " + tenantId + " AND group_id = " + groupLinkId);
        }
    }

    private String sqlLong(Long value) {
        return value == null ? "NULL" : value.toString();
    }

    private GroupFolderQuery query() {
        GroupFolderQuery query = new GroupFolderQuery();
        query.applyDataScope(scope);
        return query;
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:group_folder_mapper_test;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setUseGeneratedKeys(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(
                    new ClassPathResource("mapper/group/GroupFolderMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        GroupFolderMapper groupFolderMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupFolderMapper.class);
        }
    }
}
