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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 使用 H2 MySQL 模式执行 WhatsApp 退群事实 Mapper XML。 */
@SpringJUnitConfig(WhatsappGroupDepartedMemberMapperH2Test.TestMyBatisConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class WhatsappGroupDepartedMemberMapperH2Test {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private WhatsappGroupDepartedMemberMapper mapper;

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
                    last_exited_at BIGINT, last_exit_type VARCHAR(16),
                    last_exit_source_type VARCHAR(32)
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
                    (id, tenant_id, group_id, pn_jid, phone, last_exited_at,
                     last_exit_type, last_exit_source_type)
                VALUES
                    (711, 7, 71, '15550000001@s.whatsapp.net', '15550000001',
                     100, 'LEFT', 'HISTORY_SYNC'),
                    (712, 7, 71, '15550000003@s.whatsapp.net', '15550000003',
                     200, 'LEFT', NULL),
                    (811, 8, 81, '15550000002@s.whatsapp.net', '15550000002',
                     300, 'REMOVED', 'HISTORY_SYNC')
                """);

        assertThat(mapper.selectByGroupJids(7L, java.util.List.of("120363-test@g.us")))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.exitedAt()).isEqualTo(100L);
                    assertThat(row.exitType()).isEqualTo("LEFT");
                });
    }

    @Test
    void mapperOnlyReadsCurrentParticipantExitFacts() throws IOException {
        String xml;
        try (var input = getClass().getResourceAsStream(
                "/mapper/group/WhatsappGroupDepartedMemberMapper.xml")) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(xml)
                .contains("FROM wa_group current_group")
                .contains("JOIN wa_group_participant participant")
                .contains("participant.last_exited_at IS NOT NULL")
                .contains("WHERE current_group.tenant_id = #{tenantId}")
                .doesNotContain("whatsapp_group_departed_member")
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
            h2.setURL("jdbc:h2:mem:whatsapp_departed_member_mapper_test"
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
                    new ClassPathResource("mapper/group/WhatsappGroupDepartedMemberMapper.xml"));
            return factoryBean.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        WhatsappGroupDepartedMemberMapper mapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(WhatsappGroupDepartedMemberMapper.class);
        }
    }
}
