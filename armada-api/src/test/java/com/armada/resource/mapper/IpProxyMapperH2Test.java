package com.armada.resource.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.entity.IpProxy;
import com.armada.resource.model.enums.IpProxyCheckLifecycleStatus;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 使用 H2 MySQL 模式验证 IP 代理 Mapper 的状态流转 SQL。 */
@SpringJUnitConfig(IpProxyMapperH2Test.TestMyBatisPlusConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class IpProxyMapperH2Test {

    private static final long CURRENT_TENANT_ID = 7L;

    @org.springframework.beans.factory.annotation.Autowired
    private DataSource dataSource;

    @org.springframework.beans.factory.annotation.Autowired
    private IpProxyMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        executeSql(
                "DROP ALL OBJECTS",
                """
                CREATE TABLE ip_proxy (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    status INT NOT NULL,
                    region VARCHAR(64),
                    bound_account_id BIGINT,
                    bound_at BIGINT,
                    last_sample_check_at BIGINT,
                    detected_country_code VARCHAR(8),
                    outbound_ip VARCHAR(64),
                    detected_location VARCHAR(255),
                    detected_isp VARCHAR(255),
                    detected_latitude DECIMAL(10, 7),
                    detected_longitude DECIMAL(10, 7),
                    check_fail_count INT,
                    last_check_error VARCHAR(512),
                    check_status INT,
                    whatsapp_check_status INT,
                    whatsapp_http_status INT,
                    whatsapp_check_error VARCHAR(512),
                    updated_at BIGINT NOT NULL,
                    deleted_at BIGINT
                )
                """);
        TenantContext.set(CURRENT_TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void markFailedProxyUnavailable_marksOnlyExactCurrentTenantBinding() throws SQLException {
        executeSql(
                "INSERT INTO ip_proxy (id, tenant_id, status, bound_account_id, bound_at, check_fail_count, "
                        + "updated_at, deleted_at) VALUES (10, 7, 2, 501, 1000, 0, 1000, NULL)",
                "INSERT INTO ip_proxy (id, tenant_id, status, bound_account_id, bound_at, check_fail_count, "
                        + "updated_at, deleted_at) VALUES (11, 8, 2, 501, 1000, 0, 1000, NULL)");
        IpProxy update = failedUpdate(2_000L);

        assertThat(mapper.markFailedProxyUnavailable(
                502L, 10L, IpProxyStatus.IN_USE.code(), update)).isZero();
        assertThat(mapper.markFailedProxyUnavailable(
                501L, 11L, IpProxyStatus.IN_USE.code(), update)).isZero();
        assertThat(mapper.markFailedProxyUnavailable(
                501L, 10L, IpProxyStatus.IN_USE.code(), update)).isEqualTo(1);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT status, bound_account_id, bound_at, check_fail_count, check_status, "
                             + "whatsapp_check_status, last_check_error FROM ip_proxy WHERE id = 10")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt("status")).isEqualTo(IpProxyStatus.UNAVAILABLE.code());
            assertThat(result.getObject("bound_account_id")).isNull();
            assertThat(result.getObject("bound_at")).isNull();
            assertThat(result.getInt("check_fail_count")).isEqualTo(1);
            assertThat(result.getInt("check_status")).isEqualTo(IpProxyCheckLifecycleStatus.FAILED.code());
            assertThat(result.getInt("whatsapp_check_status"))
                    .isEqualTo(IpProxyCheckLifecycleStatus.FAILED.code());
            assertThat(result.getString("last_check_error")).isEqualTo("PROXY_FAILED");
        }
        assertThat(queryStatus(11L)).isEqualTo(IpProxyStatus.IN_USE.code());
    }

    @Test
    void updateDetectionResult_successRestoresUnavailableProxyToIdle() throws SQLException {
        executeSql("INSERT INTO ip_proxy (id, tenant_id, status, check_fail_count, last_check_error, "
                + "check_status, whatsapp_check_status, updated_at, deleted_at) "
                + "VALUES (20, 7, 3, 2, 'PROXY_FAILED', 3, 3, 1000, NULL)");
        IpProxy update = new IpProxy();
        update.setId(20L);
        update.setStatus(IpProxyStatus.IDLE.code());
        update.setLastSampleCheckAt(2_000L);
        update.setCheckFailCount(0);
        update.setCheckStatus(IpProxyCheckLifecycleStatus.SUCCESS.code());
        update.setWhatsappCheckStatus(IpProxyCheckLifecycleStatus.SUCCESS.code());
        update.setUpdatedAt(2_000L);

        assertThat(mapper.updateDetectionResult(
                update,
                IpProxyStatus.IN_USE.code(),
                IpProxyStatus.PAIRING_RESERVED.code())).isEqualTo(1);

        assertThat(queryStatus(20L)).isEqualTo(IpProxyStatus.IDLE.code());
    }

    private static IpProxy failedUpdate(long now) {
        IpProxy update = new IpProxy();
        update.setStatus(IpProxyStatus.UNAVAILABLE.code());
        update.setLastSampleCheckAt(now);
        update.setLastCheckError("PROXY_FAILED");
        update.setCheckStatus(IpProxyCheckLifecycleStatus.FAILED.code());
        update.setWhatsappCheckStatus(IpProxyCheckLifecycleStatus.FAILED.code());
        update.setWhatsappHttpStatus(null);
        update.setWhatsappCheckError("PROXY_FAILED");
        update.setUpdatedAt(now);
        return update;
    }

    private int queryStatus(long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT status FROM ip_proxy WHERE id = " + id)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private void executeSql(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** 测试专用 MyBatis-Plus 配置，复用生产租户插件与真实 Mapper XML。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestMyBatisPlusConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:ip_proxy_mapper_test;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            h2.setUser("sa");
            h2.setPassword("");
            return h2;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor mybatisPlusInterceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);

            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setConfiguration(configuration);
            factoryBean.setPlugins(mybatisPlusInterceptor);
            factoryBean.setMapperLocations(new ClassPathResource("mapper/resource/IpProxyMapper.xml"));
            return factoryBean.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        IpProxyMapper ipProxyMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(IpProxyMapper.class);
        }
    }
}
