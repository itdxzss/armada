package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.task.converter.HyperlinkTaskListConverter;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskListQuery;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskListExportFile;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskListItemVO;
import com.armada.hyperlink.task.service.HyperlinkTaskListQueryService;
import com.armada.hyperlink.task.service.impl.HyperlinkTaskListQueryServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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

/** H1 真实 Mapper XML 的租户、筛选、转义、稳定分页、准备状态和 CSV 测试。 */
@SpringJUnitConfig(HyperlinkTaskQueryServiceH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
public class HyperlinkTaskQueryServiceH2Test {

    private static final long TENANT_ID = 7L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired private DataSource dataSource;
    @org.springframework.beans.factory.annotation.Autowired private HyperlinkTaskListQueryService service;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        execute("DROP ALL OBJECTS");
        createSchema();
        execute("CREATE ALIAS JSON_CONTAINS FOR '"
                + HyperlinkTaskQueryServiceH2Test.class.getName() + ".jsonContains'");
        execute("CREATE ALIAS JSON_QUOTE FOR '"
                + HyperlinkTaskQueryServiceH2Test.class.getName() + ".jsonQuote'");
        seed();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void listIsTenantSafeStablePagedAndIncludesProvisioningRows() {
        HyperlinkTaskListQuery query = new HyperlinkTaskListQuery();
        query.setPageSize(1);

        PageResult<?> first = service.list(query);
        assertThat(first.total()).isEqualTo(6);
        assertThat(first.list()).extracting("id").containsExactly(106L);

        query.setPage(2);
        assertThat(service.list(query).list()).extracting("id").containsExactly(103L);

        query.setPageSize(999);
        query.setPage(1);
        assertThat(query.getPageSize()).isEqualTo(200);
        assertThat(service.list(query).list()).extracting("id")
                .containsExactly(106L, 103L, 102L, 101L, 105L, 104L)
                .doesNotContain(201L);
    }

    @Test
    void listExposesActualFinishTimeFromRuntimeOnlyForTerminalRows() {
        HyperlinkTaskListQuery query = new HyperlinkTaskListQuery();
        query.setPageSize(200);

        List<HyperlinkTaskListItemVO> items = service.list(query).list();

        assertThat(items).filteredOn(item -> item.id() == 105L)
                .extracting(HyperlinkTaskListItemVO::finishedAt)
                .containsExactly(2_500L);
        assertThat(items).filteredOn(item -> item.id() == 101L)
                .extracting(HyperlinkTaskListItemVO::finishedAt)
                .containsExactly((Long) null);
    }

    @Test
    void filtersHandleLiteralLikeModeCountryStatusAndHalfOpenTime() {
        HyperlinkTaskListQuery literal = new HyperlinkTaskListQuery();
        literal.setTaskName(" %_! ");
        assertThat(service.list(literal).list()).extracting("id").containsExactly(101L);

        HyperlinkTaskListQuery combined = new HyperlinkTaskListQuery();
        combined.setRunStatus(3);
        combined.setTaskMode("cycle");
        combined.setCountryIso2("us");
        combined.setCreatedAtStart(3_000L);
        combined.setCreatedAtEnd(3_001L);
        assertThat(service.list(combined).list()).extracting("id").containsExactly(102L);

        combined.setCountryIso2("UNKNOWN");
        assertThat(service.list(combined).list()).extracting("id").containsExactly(102L);

        combined.setCreatedAtEnd(3_000L);
        assertThatThrownBy(() -> service.list(combined))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("时间范围");
    }

    @Test
    void csvHasBomTwentySixColumnsAllMessageTypesFullFiltersAndHeaderOnlyResult() {
        HyperlinkTaskListExportFile all = service.export(new HyperlinkTaskListQuery());
        String csv = new String(all.bytes(), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("\uFEFF");
        assertThat(parseCsvLine(csv.lines().findFirst().orElseThrow())).hasSize(26);
        assertThat(all.exportedCount()).isEqualTo(6);
        assertThat(csv).contains("\"单图文\"", "\"双图文\"", "\"普通按钮\"", "\"卡片按钮\"");
        assertThat(csv).contains("包含国家:BR", "允许拉群:true", "\"已停用\"",
                "\"准备中\"", "\"准备失败\"");

        HyperlinkTaskListQuery literal = new HyperlinkTaskListQuery();
        literal.setTaskName("%_!");
        assertThat(service.export(literal).exportedCount()).isEqualTo(1);

        HyperlinkTaskListQuery empty = new HyperlinkTaskListQuery();
        empty.setTaskName("no-match");
        HyperlinkTaskListExportFile headerOnly = service.export(empty);
        assertThat(headerOnly.exportedCount()).isZero();
        assertThat(new String(headerOnly.bytes(), StandardCharsets.UTF_8).lines()).hasSize(1);
    }

    @Test
    void missingTenantAndInvalidFiltersFailClosed() {
        TenantContext.clear();
        assertThatThrownBy(() -> service.list(new HyperlinkTaskListQuery()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("租户");
        TenantContext.set(TENANT_ID);

        HyperlinkTaskListQuery invalid = new HyperlinkTaskListQuery();
        invalid.setRunStatus(5);
        assertThatThrownBy(() -> service.list(invalid))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("runStatus");
    }

    @Test
    void mapperSourceReadsTaskContentRuntimeAndCanonicalStrategyOnly() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/mapper/hyperlink/task/HyperlinkTaskMapper.xml")) {
            assertThat(input).isNotNull();
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String listSql = xml.substring(xml.indexOf("<sql id=\"ListFrom\">"));
            assertThat(listSql)
                    .contains("FROM hyperlink_task task", "hyperlink_task_content content",
                            "hyperlink_task_runtime runtime", "hyperlink_strategy strategy")
                    .doesNotContain("hyperlink_task_recipient ", "hyperlink_task_account_stat");
        }
    }

    /** H2 的 MySQL JSON_CONTAINS 测试别名。 */
    public static boolean jsonContains(String document, String candidate) throws Exception {
        if (document == null || candidate == null) {
            return false;
        }
        JsonNode documentNode = JSON.readTree(document);
        JsonNode candidateNode = JSON.readTree(candidate);
        if (!documentNode.isArray()) {
            return documentNode.equals(candidateNode);
        }
        for (JsonNode value : documentNode) {
            if (value.equals(candidateNode)) {
                return true;
            }
        }
        return false;
    }

    /** H2 的 MySQL JSON_QUOTE 测试别名。 */
    public static String jsonQuote(String value) throws Exception {
        return JSON.writeValueAsString(value);
    }

    private void createSchema() throws SQLException {
        execute("""
                CREATE TABLE hyperlink_task (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, task_name VARCHAR(128) NOT NULL,
                  task_planned_end_at BIGINT,
                  data_package_id BIGINT, data_package_name_snapshot VARCHAR(128),
                  target_country_iso2s_snapshot CLOB, hyperlink_strategy_id BIGINT NOT NULL,
                  is_short_link_enabled BOOLEAN NOT NULL, version INT NOT NULL, created_at BIGINT NOT NULL)
                """);
        execute("""
                CREATE TABLE hyperlink_strategy (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, strategy_scope INT NOT NULL,
                  owner_task_id BIGINT NOT NULL, task_type INT NOT NULL,
                  task_interval_minutes INT NOT NULL, account_filter CLOB NOT NULL)
                """);
        execute("""
                CREATE TABLE hyperlink_task_content (
                  hyperlink_task_id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  message_type INT NOT NULL, promotion_link VARCHAR(2048))
                """);
        execute("""
                CREATE TABLE hyperlink_task_runtime (
                  hyperlink_task_id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  is_enabled BOOLEAN NOT NULL, run_status INT NOT NULL, provision_status INT NOT NULL,
                  recipient_total INT NOT NULL, send_total BIGINT NOT NULL, success_num BIGINT NOT NULL,
                  delivered_num BIGINT NOT NULL, read_num BIGINT NOT NULL, fail_num BIGINT NOT NULL,
                  fail_404_num BIGINT NOT NULL, invalid_account_count INT NOT NULL,
                  click_uv_num INT NOT NULL, click_total BIGINT NOT NULL, used_account_count INT NOT NULL,
                  actual_concurrency INT NOT NULL, execution_duration_sec BIGINT NOT NULL,
                  active_since_at BIGINT, finished_at BIGINT, metrics_updated_at BIGINT)
                """);
    }

    private void seed() throws SQLException {
        insert(101, 7, "百分比%_!任务", 1, 1, "[\"BR\"]", 0, true, 0, true, 3_000);
        insert(102, 7, "周期任务", 3, 4, "[\"US\",null]", 2, true, 3, true, 3_000);
        insert(103, 7, "准备中", 2, 3, "[\"CN\"]", 1, true, 0, false, 4_000);
        insert(104, 7, "已停用按钮", 1, 3, "[\"PH\"]", 0, false, 4, false, 1_000);
        insert(105, 7, "双图文任务", 2, 2, "[\"CN\"]", 2, true, 2, false, 2_000);
        insert(106, 7, "准备失败", 1, 1, "[\"BR\"]", 3, true, 0, false, 5_000);
        insert(201, 8, "其他租户", 1, 1, "[\"BR\"]", 0, true, 0, true, 9_000);
    }

    private void insert(long id, long tenantId, String name, int mode, int messageType,
            String countries, int provision, boolean enabled, int runStatus,
            boolean shortLink, long createdAt) throws SQLException {
        String escapedName = name.replace("'", "''");
        String filter = "{\"filterSchemaVersion\":1,\"countryIso2s\":[\"BR\"],"
                + "\"groupInviteAllowed\":true}";
        long strategyId = id + 1_000L;
        execute("INSERT INTO hyperlink_task VALUES (" + id + "," + tenantId + ",'"
                + escapedName + "',NULL,9,'包" + id + "','" + countries + "',"
                + strategyId + "," + shortLink + ",3," + createdAt + ")");
        execute("INSERT INTO hyperlink_strategy VALUES (" + strategyId + "," + tenantId
                + ",2," + id + "," + mode + "," + (mode == 3 ? 60 : 0)
                + ",'" + filter + "')");
        execute("INSERT INTO hyperlink_task_content VALUES (" + id + "," + tenantId + ","
                + messageType + ",'https://example.com/" + id + "')");
        execute("INSERT INTO hyperlink_task_runtime VALUES (" + id + "," + tenantId + ","
                + enabled + "," + runStatus + "," + provision
                + ",100,90,80,60,30,10,4,2,5,7,8,3,120,NULL,"
                + (runStatus == 2 || runStatus == 4 ? createdAt + 500 : "NULL") + ",2900)");
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else if (character != '\uFEFF') {
                current.append(character);
            }
        }
        values.add(current.toString());
        return values;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:hyperlink_task_h1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
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
            factory.setMapperLocations(
                    new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        HyperlinkTaskMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskMapper.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        HyperlinkTaskListConverter converter(ObjectMapper objectMapper) {
            return new HyperlinkTaskListConverter(objectMapper);
        }

        @Bean
        HyperlinkTaskListQueryService service(
                HyperlinkTaskMapper mapper, HyperlinkTaskListConverter converter) {
            return new HyperlinkTaskListQueryServiceImpl(mapper, converter);
        }
    }
}
