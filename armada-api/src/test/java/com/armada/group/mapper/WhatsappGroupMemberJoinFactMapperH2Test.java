package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
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

/** 使用 H2 MySQL 模式执行 WhatsApp 进群事实 Mapper XML。 */
@SpringJUnitConfig(WhatsappGroupMemberJoinFactMapperH2Test.TestMyBatisConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class WhatsappGroupMemberJoinFactMapperH2Test {

    @org.springframework.beans.factory.annotation.Autowired
    private DataSource dataSource;

    @org.springframework.beans.factory.annotation.Autowired
    private WhatsappGroupMemberJoinFactMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        executeSql("DROP ALL OBJECTS", """
                CREATE TABLE wa_group (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                    group_jid VARCHAR(128) NOT NULL
                )
                """, """
                CREATE TABLE wa_group_participant (
                    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_id BIGINT NOT NULL,
                    pn_jid VARCHAR(191), lid_jid VARCHAR(191), phone VARCHAR(32),
                    last_joined_at BIGINT
                )
                """);
    }

    @Test
    void selectByGroupJidsIsTenantIsolated() throws SQLException {
        executeSql("""
                INSERT INTO wa_group (id, tenant_id, group_jid)
                VALUES (71, 7, '120363-test@g.us'), (81, 8, '120363-test@g.us')
                """, """
                INSERT INTO wa_group_participant
                    (id, tenant_id, group_id, pn_jid, phone, last_joined_at)
                VALUES
                    (711, 7, 71, '15550000001@s.whatsapp.net', '15550000001', 100),
                    (811, 8, 81, '15550000002@s.whatsapp.net', '15550000002', 300)
                """);

        assertThat(mapper.selectByGroupJids(7L, java.util.List.of("120363-test@g.us")))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.phone()).isEqualTo("15550000001");
                    assertThat(row.joinedAt()).isEqualTo(100L);
                });
    }

    @Test
    void mapperOnlyReadsCurrentParticipantJoinFacts() throws IOException {
        String xml;
        try (var input = getClass().getResourceAsStream(
                "/mapper/group/WhatsappGroupMemberJoinFactMapper.xml")) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(xml)
                .contains("FROM wa_group current_group")
                .contains("JOIN wa_group_participant participant")
                .contains("participant.last_joined_at IS NOT NULL")
                .contains("WHERE current_group.tenant_id = #{tenantId}")
                .doesNotContain("whatsapp_group_member_join_fact")
                .doesNotContain("<insert")
                .doesNotContain("<update");
    }

    private void executeSql(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestMyBatisConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:whatsapp_group_join_fact_mapper_test"
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            h2.setUser("sa");
            h2.setPassword("");
            return h2;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor mybatisPlusInterceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setUseGeneratedKeys(true);

            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setConfiguration(configuration);
            factoryBean.setPlugins(mybatisPlusInterceptor);
            factoryBean.setMapperLocations(
                    new ClassPathResource("mapper/group/WhatsappGroupMemberJoinFactMapper.xml"));
            return factoryBean.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        WhatsappGroupMemberJoinFactMapper mapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(WhatsappGroupMemberJoinFactMapper.class);
        }
    }
}
