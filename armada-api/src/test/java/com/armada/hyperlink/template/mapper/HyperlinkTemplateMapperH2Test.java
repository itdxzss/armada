package com.armada.hyperlink.template.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.template.model.dto.HyperlinkTemplateQuery;
import com.armada.hyperlink.template.model.entity.HyperlinkTemplate;
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

/** 超链模板真实 Mapper XML、分页和租户插件的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(HyperlinkTemplateMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkTemplateMapperH2Test {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private HyperlinkTemplateMapper mapper;

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
    void insertListAndDetailRoundTripButtonsWithinCurrentTenant() throws SQLException {
        HyperlinkTemplate current = template("福利按钮", 3, 100L);
        current.setButtons("[{\"type\":\"CTA_URL\",\"sort\":1}]");
        assertThat(mapper.insert(current)).isEqualTo(1);
        execute("""
                INSERT INTO hyperlink_template
                    (tenant_id, template_name, message_type, message_schema_version, title,
                     buttons, version, created_at, updated_at)
                VALUES
                    (8, '其他租户福利', 3, 1, '不可见', '[]', 1, 100, 100)
                """);

        HyperlinkTemplateQuery query = new HyperlinkTemplateQuery();
        query.setName("福利");
        query.setMessageType(3);
        query.setCreatedFrom(100L);
        query.setCreatedTo(100L);

        assertThat(mapper.countPage(query)).isEqualTo(1);
        assertThat(mapper.selectPage(query))
                .singleElement()
                .satisfies(found -> {
                    assertThat(found.getId()).isEqualTo(current.getId());
                    assertThat(found.getTenantId()).isEqualTo(7L);
                    assertThat(found.getButtons()).isEqualTo(current.getButtons());
                });
        assertThat(mapper.selectById(current.getId()).getTemplateName()).isEqualTo("福利按钮");

        TenantContext.set(8L);
        assertThat(mapper.selectById(current.getId())).isNull();
    }

    @Test
    void optimisticUpdateRejectsOldVersionAndIncrementsCurrentVersion() {
        HyperlinkTemplate row = template("待编辑", 1, 100L);
        mapper.insert(row);

        HyperlinkTemplate update = template("已编辑", 4, 200L);
        update.setId(row.getId());
        assertThat(mapper.updateByIdAndVersion(update, 1)).isEqualTo(1);
        assertThat(mapper.updateByIdAndVersion(update, 1)).isZero();

        HyperlinkTemplate found = mapper.selectById(row.getId());
        assertThat(found.getTemplateName()).isEqualTo("已编辑");
        assertThat(found.getMessageType()).isEqualTo(4);
        assertThat(found.getVersion()).isEqualTo(2);
        assertThat(found.getUpdatedAt()).isEqualTo(200L);
    }

    @Test
    void softDeleteHidesRowAndAllowsNameReuse() {
        HyperlinkTemplate row = template("可复用", 1, 100L);
        mapper.insert(row);

        assertThat(mapper.softDelete(row.getId(), 200L)).isEqualTo(1);
        assertThat(mapper.selectById(row.getId())).isNull();
        assertThat(mapper.existsByName("可复用", null)).isFalse();

        HyperlinkTemplate replacement = template("可复用", 3, 300L);
        assertThat(mapper.insert(replacement)).isEqualTo(1);
        assertThat(replacement.getId()).isNotEqualTo(row.getId());
    }

    private static HyperlinkTemplate template(String name, int type, long timestamp) {
        HyperlinkTemplate row = new HyperlinkTemplate();
        row.setTemplateName(name);
        row.setMessageType(type);
        row.setMessageSchemaVersion(1);
        row.setTitle("标题");
        row.setButtons("[]");
        row.setVersion(1);
        row.setCreatedAt(timestamp);
        row.setUpdatedAt(timestamp);
        return row;
    }

    private void resetSchema() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE hyperlink_template (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    template_name VARCHAR(128) NOT NULL,
                    message_type TINYINT NOT NULL,
                    message_schema_version INT NOT NULL DEFAULT 1,
                    title VARCHAR(512) NOT NULL,
                    content TEXT,
                    link_description VARCHAR(512),
                    promotion_link VARCHAR(2048),
                    buttons VARCHAR(4096),
                    card_text VARCHAR(500),
                    link_preview_asset_id BIGINT,
                    body_main_asset_id BIGINT,
                    remark VARCHAR(255),
                    version INT NOT NULL DEFAULT 1,
                    created_by BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    deleted_at BIGINT,
                    is_active TINYINT GENERATED ALWAYS AS
                        (CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END),
                    UNIQUE (tenant_id, template_name, is_active)
                )
                """);
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
            dataSource.setURL("jdbc:h2:mem:hyperlink_template_mapper_test;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
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
                    "mapper/hyperlink/template/HyperlinkTemplateMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        HyperlinkTemplateMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTemplateMapper.class);
        }
    }
}
