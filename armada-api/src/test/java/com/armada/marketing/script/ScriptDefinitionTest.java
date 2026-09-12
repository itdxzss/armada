package com.armada.marketing.script;

import com.armada.boot.config.MyBatisConfig;
import com.armada.marketing.converter.MarketingTemplateConverter;
import com.armada.marketing.converter.ScriptMarketingConverter;
import com.armada.marketing.mapper.ScriptMarketingDefinitionMapper;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.dto.ScriptDefinitionSaveDTO;
import com.armada.marketing.model.dto.ScriptMarketingQuery;
import com.armada.marketing.model.dto.ScriptMarketingStepDTO;
import com.armada.marketing.script.service.ScriptDefinitionService;
import com.armada.marketing.script.service.ScriptMarketingContentService;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.marketing.service.MarketingTemplateFileService;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** H2 验证剧本定义的真实 SQL、事务、创建人和租户边界。 */
class ScriptDefinitionTest {
    AnnotationConfigApplicationContext context;
    ScriptDefinitionService service;
    ScriptMarketingContentService content;
    @BeforeEach void setup() throws Exception {
        context = new AnnotationConfigApplicationContext(Config.class);
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.execute("DROP ALL OBJECTS");
        String ddl = new ClassPathResource("db/migration/V187__script_definition_library.sql")
                .getContentAsString(StandardCharsets.UTF_8).split("-- 保留既有任务")[0];
        jdbc.execute(ddl.replace("steps_json JSON", "steps_json LONGTEXT"));
        TenantContext.set(7L); service = context.getBean(ScriptDefinitionService.class);
        content = context.getBean(ScriptMarketingContentService.class);
    }
    @AfterEach void cleanup() { TenantContext.clear(); context.close(); }
    @Test void savedDefinitionIsReusableWhileExistingSnapshotAndOwnerStayIndependent() {
        var first = service.create(dto("首版", true, "hello"), 11L);
        String taskSnapshot = content.encode(first.steps());
        service.update(first.id(), dto("第二版", false, "updated"), 11L);
        assertThat(content.decode(taskSnapshot).get(0).message().content()).isEqualTo("hello");
        assertThat(service.detail(first.id(), 11L).steps().get(0).message().content()).isEqualTo("updated");
        assertThatThrownBy(() -> service.update(first.id(), dto("他人", true, "invalid"), 12L)).hasMessageContaining("无权");
        assertThat(service.detail(first.id(), 11L).name()).isEqualTo("第二版");
        service.delete(first.id(), 11L);
        assertThatThrownBy(() -> service.detail(first.id(), 11L)).hasMessageContaining("不存在");
        assertThat(content.decode(taskSnapshot).get(0).message().content()).isEqualTo("hello");
    }
    @Test void sqlPaginationEnabledFilterAndTenantScopeStayAligned() {
        service.create(dto("启用 A", true, "hello"), 11L);
        service.create(dto("停用 B", false, "hello"), 11L);
        service.create(dto("其他用户", true, "hello"), 12L);
        var query = new ScriptMarketingQuery(); query.setPageSize(1);
        assertThat(service.list(query, 11L).total()).isEqualTo(2);
        assertThat(service.list(query, 11L).list()).hasSize(1);
        query.setStatus(1); assertThat(service.list(query, 11L).list()).singleElement().satisfies(row -> assertThat(row.name()).isEqualTo("启用 A"));
        TenantContext.set(8L); assertThat(service.list(query, 11L).total()).isZero();
        TenantContext.clear(); assertThat(service.list(query, 11L).total()).isZero();
    }
    @Test void invalidRoleOrIntervalCannotLeaveAPartiallySavedDefinition() {
        var valid = dto("bad", true, "hello");
        var invalid = new ScriptMarketingStepDTO(null, null, valid.steps().get(0).message(), "A", 0, 0, null, null);
        assertThatThrownBy(() -> service.create(new ScriptDefinitionSaveDTO("bad", true, List.of(invalid, valid.steps().get(1))), 11L))
                .hasMessageContaining("角色");
        assertThat(service.list(new ScriptMarketingQuery(), 11L).total()).isZero();
    }
    ScriptDefinitionSaveDTO dto(String name, boolean enabled, String text) {
        var message = new MarketingTemplateDTO("", 1, null, null, text, null, null, null, null, false);
        return new ScriptDefinitionSaveDTO(name, enabled, List.of(
                new ScriptMarketingStepDTO("ADMIN", null, message, "A", 0, 0, null, null),
                new ScriptMarketingStepDTO("PROMOTER", null, message, "P1", 10, 20, null, null)));
    }
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({MyBatisConfig.class, ScriptDefinitionService.class})
    static class Config {
        @Bean DataSource dataSource() {
            var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:script_definition;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"); return ds;
        }
        @Bean DataSourceTransactionManager tx(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionTemplate session(DataSource ds, MybatisPlusInterceptor plugin) throws Exception {
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true); config.setUseGeneratedKeys(true);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config); factory.setPlugins(plugin);
            factory.setMapperLocations(new ClassPathResource("mapper/marketing/ScriptMarketingDefinitionMapper.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }
        @Bean ScriptMarketingDefinitionMapper mapper(SqlSessionTemplate session) { return session.getMapper(ScriptMarketingDefinitionMapper.class); }
        @Bean ScriptMarketingConverter converter() { return Mappers.getMapper(ScriptMarketingConverter.class); }
        @Bean MarketingTemplateFileService assets() { return mock(MarketingTemplateFileService.class); }
        @Bean ScriptMarketingContentService content() {
            return new ScriptMarketingContentService(new ObjectMapper(), Mappers.getMapper(MarketingTemplateConverter.class),
                    new MarketingMessageComposer(), null, null);
        }
    }
}
