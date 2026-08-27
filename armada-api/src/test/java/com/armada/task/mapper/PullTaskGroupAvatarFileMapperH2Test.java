package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.security.DataScope;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTaskGroupAvatarFile;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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

/** 使用生产 Mapper XML 验证拉群头像元数据的用户与租户隔离。 */
@SpringJUnitConfig(PullTaskGroupAvatarFileMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskGroupAvatarFileMapperH2Test {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private PullTaskGroupAvatarFileMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        execute("DROP ALL OBJECTS", """
                CREATE TABLE pull_task_group_avatar_file (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    file_key VARCHAR(64) NOT NULL,
                    owner_user_id BIGINT NOT NULL,
                    created_at BIGINT NOT NULL,
                    UNIQUE (tenant_id, file_key)
                )
                """, """
                INSERT INTO pull_task_group_avatar_file
                    (tenant_id, file_key, owner_user_id, created_at)
                VALUES
                    (7, '11111111111111111111111111111111.png', 1001, 1),
                    (7, '22222222222222222222222222222222.png', 1002, 2),
                    (8, '11111111111111111111111111111111.png', 1002, 3)
                """);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void selfAllMissingAndSystemScopesFailClosedAsDesigned() {
        String u1Key = "11111111111111111111111111111111.png";
        String u2Key = "22222222222222222222222222222222.png";

        assertThat(mapper.selectByFileKeyForScope(u1Key, DataScope.self(1001L))).isNotNull();
        assertThat(mapper.selectByFileKeyForScope(u2Key, DataScope.self(1001L))).isNull();
        assertThat(mapper.selectByFileKeyForScope(u2Key, DataScope.all(9001L))).isNotNull();
        assertThat(mapper.selectByFileKeyForScope(u1Key, null)).isNull();
        assertThat(mapper.selectByFileKeyForScope(
                u1Key, DataScope.system("avatar cleanup"))).isNull();

        TenantContext.set(8L);
        assertThat(mapper.selectByFileKeyForScope(u1Key, DataScope.self(1001L))).isNull();
        assertThat(mapper.selectByFileKeyForScope(u1Key, DataScope.all(9001L)))
                .extracting(PullTaskGroupAvatarFile::getOwnerUserId)
                .isEqualTo(1002L);
    }

    @Test
    void insertAndDeleteStayInsideCurrentTenant() {
        PullTaskGroupAvatarFile row = new PullTaskGroupAvatarFile();
        row.setFileKey("33333333333333333333333333333333.jpg");
        row.setOwnerUserId(1001L);
        row.setCreatedAt(4L);

        assertThat(mapper.insert(row)).isEqualTo(1);
        assertThat(mapper.selectByFileKeyForScope(
                row.getFileKey(), DataScope.self(1001L))).isNotNull();
        assertThat(mapper.deleteByFileKey(row.getFileKey())).isEqualTo(1);
        assertThat(mapper.selectByFileKeyForScope(
                row.getFileKey(), DataScope.all(9001L))).isNull();

        String sharedKey = "11111111111111111111111111111111.png";
        assertThat(mapper.deleteByFileKey(sharedKey)).isEqualTo(1);
        TenantContext.set(8L);
        assertThat(mapper.selectByFileKeyForScope(sharedKey, DataScope.all(9001L)))
                .isNotNull();
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** 只加载头像元数据生产 XML 与生产租户插件。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:pull_task_avatar_file_mapper_test;"
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
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource(
                    "mapper/task/PullTaskGroupAvatarFileMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        PullTaskGroupAvatarFileMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupAvatarFileMapper.class);
        }
    }
}
