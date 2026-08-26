package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.enums.GroupClassification;
import com.armada.group.model.enums.GroupClassificationSource;
import com.armada.group.model.vo.CanonicalGroupClassificationWrite;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** canonical 群首次唯一分类的 H2 MySQL 模式 Mapper 测试。 */
@SpringJUnitConfig(GroupClassificationMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class GroupClassificationMapperH2Test {

    private static final long TENANT_ID = 7L;
    private static final String GROUP_JID = "120363-canonical@g.us";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GroupClassificationMapper mapper;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws SQLException {
        jdbc = new JdbcTemplate(dataSource);
        execute("DROP ALL OBJECTS", """
                CREATE TABLE wa_group (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) NOT NULL,
                  origin TINYINT NOT NULL,
                  group_classification TINYINT NOT NULL DEFAULT 0,
                  group_classified_at BIGINT,
                  group_classification_source TINYINT,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  deleted_at BIGINT,
                  CONSTRAINT uq_wa_group_jid UNIQUE (tenant_id, group_jid),
                  CONSTRAINT ck_wa_group_classification CHECK (group_classification IN (0, 1, 2)),
                  CONSTRAINT ck_wa_group_classification_source CHECK (
                    group_classification_source IS NULL
                    OR group_classification_source IN (1, 2, 3, 4)
                  ),
                  CONSTRAINT ck_wa_group_classification_header CHECK (
                    (group_classification = 0
                      AND group_classified_at IS NULL
                      AND group_classification_source IS NULL)
                    OR (group_classification IN (1, 2)
                      AND group_classified_at IS NOT NULL
                      AND group_classification_source IS NOT NULL)
                  )
                )
                """);
    }

    @Test
    void firstSuccessfulWriteWinsAndLaterOppositeFactCannotOverwriteEvidence() {
        assertThat(mapper.ensureCanonicalGroup(TENANT_ID, GROUP_JID, 1_000L)).isOne();

        assertThat(mapper.classifyFirst(
                TENANT_ID,
                GROUP_JID,
                GroupClassification.HISTORICAL.code(),
                GroupClassificationSource.BASELINE_CAPTURED.code(),
                1_100L,
                1_200L)).isOne();
        assertThat(mapper.classifyFirst(
                TENANT_ID,
                GROUP_JID,
                GroupClassification.POST_CONTROL.code(),
                GroupClassificationSource.POST_CONTROL_DISCOVERED.code(),
                900L,
                1_300L)).isZero();

        assertThat(jdbc.queryForMap("""
                SELECT group_classification, group_classified_at,
                       group_classification_source, updated_at
                FROM wa_group
                WHERE tenant_id = ? AND group_jid = ?
                """, TENANT_ID, GROUP_JID))
                .containsEntry("group_classification", 1)
                .containsEntry("group_classified_at", 1_100L)
                .containsEntry("group_classification_source", 1)
                .containsEntry("updated_at", 1_200L);
    }

    @Test
    void sameJidIsClassifiedIndependentlyPerTenant() {
        mapper.ensureCanonicalGroup(TENANT_ID, GROUP_JID, 1_000L);
        mapper.ensureCanonicalGroup(8L, GROUP_JID, 1_000L);

        assertThat(mapper.classifyFirst(
                TENANT_ID,
                GROUP_JID,
                GroupClassification.HISTORICAL.code(),
                GroupClassificationSource.BASELINE_CAPTURED.code(),
                1_100L,
                1_100L)).isOne();
        assertThat(mapper.classifyFirst(
                8L,
                GROUP_JID,
                GroupClassification.POST_CONTROL.code(),
                GroupClassificationSource.POST_CONTROL_DISCOVERED.code(),
                1_200L,
                1_200L)).isOne();

        assertThat(jdbc.queryForList("""
                SELECT tenant_id, group_classification
                FROM wa_group
                WHERE group_jid = ?
                ORDER BY tenant_id
                """, GROUP_JID))
                .extracting(row -> row.get("group_classification"))
                .containsExactly(1, 2);
    }

    @Test
    void concurrentOppositeCandidatesProduceExactlyOneWinner() throws Exception {
        mapper.ensureCanonicalGroup(TENANT_ID, GROUP_JID, 1_000L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> historical = executor.submit(() -> classifyAfterBarrier(
                    ready,
                    start,
                    GroupClassification.HISTORICAL.code(),
                    GroupClassificationSource.BASELINE_CAPTURED.code()));
            Future<Integer> postControl = executor.submit(() -> classifyAfterBarrier(
                    ready,
                    start,
                    GroupClassification.POST_CONTROL.code(),
                    GroupClassificationSource.POST_CONTROL_DISCOVERED.code()));
            ready.await();
            start.countDown();

            assertThat(List.of(historical.get(), postControl.get()))
                    .containsExactlyInAnyOrder(0, 1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("""
                SELECT group_classification
                FROM wa_group
                WHERE tenant_id = ? AND group_jid = ?
                """, String.class, TENANT_ID, GROUP_JID))
                .isIn("1", "2");
    }

    @Test
    void unclassifiedCandidateIsRejectedWithoutWritingEvidence() {
        mapper.ensureCanonicalGroup(TENANT_ID, GROUP_JID, 1_000L);

        assertThat(mapper.classifyFirst(
                TENANT_ID,
                GROUP_JID,
                GroupClassification.UNCLASSIFIED.code(),
                GroupClassificationSource.BASELINE_CAPTURED.code(),
                1_100L,
                1_200L)).isZero();

        assertThat(jdbc.queryForMap("""
                SELECT group_classification, group_classified_at,
                       group_classification_source
                FROM wa_group
                WHERE tenant_id = ? AND group_jid = ?
                """, TENANT_ID, GROUP_JID))
                .containsEntry("group_classification", 0)
                .containsEntry("group_classified_at", null)
                .containsEntry("group_classification_source", null);
    }

    @Test
    void batchWriteClassifiesAllLockedUnclassifiedRowsInOneStatement() {
        String secondGroupJid = "120363-zcanonical@g.us";
        assertThat(mapper.ensureCanonicalGroups(
                TENANT_ID, List.of(GROUP_JID, secondGroupJid), 1_000L)).isEqualTo(2);
        assertThat(mapper.selectByGroupJids(
                TENANT_ID, List.of(GROUP_JID, secondGroupJid)))
                .extracting(row -> row.classificationCode())
                .containsExactly(0, 0);

        assertThat(mapper.classifyFirstBatch(
                TENANT_ID,
                List.of(
                        new CanonicalGroupClassificationWrite(
                                GROUP_JID,
                                GroupClassification.HISTORICAL.code(),
                                GroupClassificationSource.BASELINE_CAPTURED.code(),
                                1_100L),
                        new CanonicalGroupClassificationWrite(
                                secondGroupJid,
                                GroupClassification.POST_CONTROL.code(),
                                GroupClassificationSource.POST_CONTROL_DISCOVERED.code(),
                                1_200L)),
                1_300L)).isEqualTo(2);

        assertThat(jdbc.queryForList("""
                SELECT group_jid, group_classification, group_classified_at,
                       group_classification_source
                FROM wa_group
                WHERE tenant_id = ?
                ORDER BY group_jid
                """, TENANT_ID))
                .extracting(
                        row -> row.get("group_classification"),
                        row -> row.get("group_classified_at"),
                        row -> row.get("group_classification_source"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 1_100L, 1),
                        org.assertj.core.groups.Tuple.tuple(2, 1_200L, 2));
    }

    @Test
    void winnerReadUsesLockingCurrentReadInsteadOfRepeatableReadSnapshot() throws Exception {
        String mapperXml;
        try (java.io.InputStream input = new ClassPathResource(
                "mapper/group/GroupClassificationMapper.xml").getInputStream()) {
            mapperXml = new String(
                    input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        assertThat(mapperXml.substring(
                mapperXml.indexOf("<select id=\"selectByGroupJids\""),
                mapperXml.indexOf("</select>", mapperXml.indexOf(
                        "<select id=\"selectByGroupJids\""))))
                .contains("ORDER BY group_jid ASC")
                .contains("FOR UPDATE");
    }

    private int classifyAfterBarrier(
            CountDownLatch ready,
            CountDownLatch start,
            int classificationCode,
            int sourceCode) throws InterruptedException {
        ready.countDown();
        start.await();
        return mapper.classifyFirst(
                TENANT_ID, GROUP_JID, classificationCode, sourceCode, 1_100L, 1_200L);
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** 测试加载生产 Mapper XML 与生产租户插件。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:group_classification_mapper_test;"
                    + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
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
                    "mapper/group/GroupClassificationMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        GroupClassificationMapper groupClassificationMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupClassificationMapper.class);
        }
    }
}
