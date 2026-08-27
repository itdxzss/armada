package com.armada.marketing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.marketing.model.dto.MarketingTemplateQuery;
import com.armada.shared.security.DataScope;
import com.armada.shared.tenant.TenantContext;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;

/** 使用生产 Mapper XML 验证营销模板和图片文件的用户/租户隔离。 */
@SpringJUnitConfig(MarketingTemplateUserDataScopeMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class MarketingTemplateUserDataScopeMapperH2Test {

    private static final long TENANT_ID = 7L;
    private static final long OTHER_TENANT_ID = 8L;
    private static final long USER_ONE_ID = 1001L;
    private static final long USER_TWO_ID = 1002L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private MarketingTemplateMapper templateMapper;
    @Autowired
    private MarketingTemplateFileMapper fileMapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        execute("DROP ALL OBJECTS");
        createSchema();
        insertFixtures();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void selfScopeReturnsOnlyOwnTemplatesAndFiles() {
        assertThat(countTemplates(DataScope.self(USER_ONE_ID))).isEqualTo(1);
        assertThat(countTemplates(DataScope.self(USER_TWO_ID))).isEqualTo(1);

        assertThat(templateMapper.selectByIdForScope(101L, DataScope.self(USER_ONE_ID))).isNotNull();
        assertThat(templateMapper.selectByIdForScope(102L, DataScope.self(USER_ONE_ID))).isNull();
        assertThat(templateMapper.selectByIdForScope(103L, DataScope.self(USER_ONE_ID))).isNull();
        assertThat(fileMapper.selectByIdForScope(201L, DataScope.self(USER_ONE_ID))).isNotNull();
        assertThat(fileMapper.selectByIdForScope(202L, DataScope.self(USER_ONE_ID))).isNull();
        assertThat(fileMapper.selectByIdForScope(203L, DataScope.self(USER_ONE_ID))).isNull();
    }

    @Test
    void allScopeIncludesHistoricalNullButNeverCrossesTenant() {
        assertThat(countTemplates(DataScope.all(9001L))).isEqualTo(3);
        assertThat(templateMapper.selectByIdForScope(103L, DataScope.all(9001L))).isNotNull();
        assertThat(fileMapper.selectByIdForScope(203L, DataScope.all(9001L))).isNotNull();
        assertThat(templateMapper.selectByIdForScope(105L, DataScope.all(9001L))).isNull();
        assertThat(fileMapper.selectByIdForScope(205L, DataScope.all(9001L))).isNull();

        try {
            TenantContext.set(OTHER_TENANT_ID);
            assertThat(countTemplates(DataScope.all(9001L))).isEqualTo(1);
        } finally {
            TenantContext.set(TENANT_ID);
        }
    }

    @Test
    void missingAndSystemScopeFailClosed() {
        assertThat(countTemplates(null)).isZero();
        assertThat(countTemplates(DataScope.system("template maintenance"))).isZero();
        assertThat(templateMapper.selectByIdForScope(101L, null)).isNull();
        assertThat(fileMapper.selectByIdForScope(
                201L, DataScope.system("template maintenance"))).isNull();
    }

    private long countTemplates(DataScope scope) {
        MarketingTemplateQuery query = new MarketingTemplateQuery();
        query.applyDataScope(scope);
        return templateMapper.countPage(query);
    }

    private void createSchema() throws SQLException {
        execute("""
                CREATE TABLE marketing_template (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  owner_user_id BIGINT,
                  template_name VARCHAR(128) NOT NULL,
                  link_mode INT NOT NULL,
                  text_type VARCHAR(64),
                  image_file_id BIGINT,
                  content VARCHAR(1000),
                  body_text VARCHAR(1000),
                  buttons VARCHAR(1000),
                  promotion_link VARCHAR(512),
                  mention_all BOOLEAN,
                  remark VARCHAR(255),
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  created_by BIGINT,
                  deleted_at BIGINT
                )
                """, """
                CREATE TABLE marketing_template_file (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  owner_user_id BIGINT,
                  original_filename VARCHAR(255) NOT NULL,
                  content_type VARCHAR(128) NOT NULL,
                  size_bytes BIGINT NOT NULL,
                  content BLOB NOT NULL,
                  created_at BIGINT NOT NULL,
                  deleted_at BIGINT
                )
                """);
    }

    private void insertFixtures() throws SQLException {
        execute("""
                INSERT INTO marketing_template
                  (id, tenant_id, owner_user_id, template_name, link_mode, mention_all,
                   created_at, updated_at, deleted_at)
                VALUES
                  (101, 7, 1001, 'u1', 1, FALSE, 1, 1, NULL),
                  (102, 7, 1002, 'u2', 1, FALSE, 1, 1, NULL),
                  (103, 7, NULL, 'history', 1, FALSE, 1, 1, NULL),
                  (104, 7, 1001, 'deleted', 1, FALSE, 1, 1, 99),
                  (105, 8, 1001, 'other-tenant', 1, FALSE, 1, 1, NULL)
                """, """
                INSERT INTO marketing_template_file
                  (id, tenant_id, owner_user_id, original_filename, content_type,
                   size_bytes, content, created_at, deleted_at)
                VALUES
                  (201, 7, 1001, 'u1.png', 'image/png', 1, X'01', 1, NULL),
                  (202, 7, 1002, 'u2.png', 'image/png', 1, X'02', 1, NULL),
                  (203, 7, NULL, 'history.png', 'image/png', 1, X'03', 1, NULL),
                  (204, 7, 1001, 'deleted.png', 'image/png', 1, X'04', 1, 99),
                  (205, 8, 1001, 'other-tenant.png', 'image/png', 1, X'05', 1, NULL)
                """);
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** 只加载本切片的生产 XML 和生产租户插件。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:marketing_template_user_scope_mapper_test;"
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
            factory.setMapperLocations(
                    new ClassPathResource("mapper/marketing/MarketingTemplateMapper.xml"),
                    new ClassPathResource("mapper/marketing/MarketingTemplateFileMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        MarketingTemplateMapper marketingTemplateMapper(SqlSessionTemplate template) {
            return template.getMapper(MarketingTemplateMapper.class);
        }

        @Bean
        MarketingTemplateFileMapper marketingTemplateFileMapper(SqlSessionTemplate template) {
            return template.getMapper(MarketingTemplateFileMapper.class);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
