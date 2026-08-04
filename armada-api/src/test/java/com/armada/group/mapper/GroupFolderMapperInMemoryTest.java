package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.entity.GroupFolder;
import com.armada.group.model.vo.GroupFolderVO;
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

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
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

        GroupFolderQuery query = new GroupFolderQuery();
        List<GroupFolderVO> rows = mapper.selectPage(query);

        assertThat(mapper.countPage(query)).isEqualTo(1);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("印度组");
            assertThat(row.groupCount()).isEqualTo(1);
        });
        assertThat(mapper.selectUsableLinks(folder.getId()))
                .containsExactly("chat.whatsapp.com/AVAILABLE");
    }

    @Test
    void clearingFolderLinksDoesNotDeleteGroupEntries() throws SQLException {
        GroupFolder folder = folder("待删除", 100L);
        mapper.insert(folder);
        insertLink(1L, 7L, folder.getId(), "chat.whatsapp.com/ONE", null);
        insertLink(2L, 7L, folder.getId(), "chat.whatsapp.com/TWO", null);

        assertThat(mapper.clearGroupLinks(List.of(folder.getId()), 500L)).isEqualTo(2);
        assertThat(mapper.softDeleteByIds(List.of(folder.getId()), 500L)).isEqualTo(1);

        assertThat(count("SELECT COUNT(*) FROM group_link WHERE deleted_at IS NULL"))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM group_link WHERE folder_id IS NULL"))
                .isEqualTo(2);
        assertThat(mapper.selectActiveById(folder.getId())).isNull();
    }

    @Test
    void otherTenantCannotReadOrMutateFolder() throws SQLException {
        GroupFolder folder = folder("租户七", 100L);
        mapper.insert(folder);
        insertLink(1L, 7L, folder.getId(), "chat.whatsapp.com/ONE", null);
        insertHealth(1L, 7L, 1L, 1, 0);

        TenantContext.set(8L);
        assertThat(mapper.selectActiveById(folder.getId())).isNull();
        assertThat(mapper.selectUsableLinks(folder.getId())).isEmpty();
        assertThat(mapper.clearGroupLinks(List.of(folder.getId()), 500L)).isZero();
        assertThat(mapper.softDeleteByIds(List.of(folder.getId()), 500L)).isZero();
    }

    private GroupFolder folder(String name, long now) {
        GroupFolder row = new GroupFolder();
        row.setName(name);
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
                    name VARCHAR(100) NOT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    created_by BIGINT,
                    deleted_at BIGINT,
                    CONSTRAINT uq_group_folder_name UNIQUE (tenant_id, name)
                )
                """);
        execute("""
                CREATE TABLE group_link (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    link_url VARCHAR(255) NOT NULL,
                    folder_id BIGINT,
                    updated_at BIGINT NOT NULL,
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
    }

    private void insertLink(long id, long tenantId, long folderId,
                            String link, Long deletedAt) throws SQLException {
        execute("INSERT INTO group_link "
                + "(id, tenant_id, link_url, folder_id, updated_at, deleted_at) VALUES ("
                + id + ", " + tenantId + ", '" + link + "', " + folderId + ", 100, "
                + (deletedAt == null ? "NULL" : deletedAt) + ")");
    }

    private void insertHealth(long id, long tenantId, long groupLinkId,
                              int status, int banned) throws SQLException {
        execute("INSERT INTO group_link_health "
                + "(id, tenant_id, group_link_id, health_status, is_banned, created_at, updated_at) "
                + "VALUES (" + id + ", " + tenantId + ", " + groupLinkId + ", "
                + status + ", " + banned + ", 100, 100)");
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
