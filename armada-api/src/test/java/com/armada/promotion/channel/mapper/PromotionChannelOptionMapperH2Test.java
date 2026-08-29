package com.armada.promotion.channel.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 超链创建上下文读取启用渠道时保持租户隔离和稳定排序。 */
@SpringJUnitConfig(PromotionChannelOptionMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PromotionChannelOptionMapperH2Test {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private PromotionChannelMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE promotion_channel (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  channel_name VARCHAR(128), status INT, deleted_at BIGINT)
                """);
        execute("INSERT INTO promotion_channel VALUES "
                + "(1,7,'Z渠道',1,NULL),(2,7,'A渠道',1,NULL),(3,7,'禁用',0,NULL),"
                + "(4,7,'删除',1,100),(5,8,'其他租户',1,NULL)");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void selectsOnlyCurrentTenantEnabledActiveChannels() {
        assertThat(mapper.selectOptions())
                .extracting(option -> option.id() + ":" + option.name())
                .containsExactly("2:A渠道", "1:Z渠道");

        TenantContext.set(8L);
        assertThat(mapper.selectOptions())
                .extracting(option -> option.id() + ":" + option.name())
                .containsExactly("5:其他租户");
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
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:promotion_channel_option;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource, MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource(
                    "mapper/promotion/channel/PromotionChannelMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        PromotionChannelMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(PromotionChannelMapper.class);
        }
    }
}
