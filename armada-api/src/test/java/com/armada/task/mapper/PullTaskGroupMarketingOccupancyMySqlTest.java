package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTaskGroupMarketingGroupOccupancy;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 使用 Docker MySQL 8.4 验证 V089 和生产占用 Mapper 的真实行为。 */
@Testcontainers(disabledWithoutDocker = true)
class PullTaskGroupMarketingOccupancyMySqlTest {

    private static final String GROUP_JID = "120363001@g.us";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("armada_group_marketing_test")
            .withUsername("armada")
            .withPassword("armada")
            .withCommand("--transaction-isolation=READ-COMMITTED");

    private static JdbcTemplate jdbc;
    private static PullTaskGroupMarketingGroupOccupancyMapper occupancyMapper;

    @BeforeAll
    static void configureProductionMapperAndMigration() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(readMigration());
        occupancyMapper = buildSqlSessionTemplate(dataSource)
                .getMapper(PullTaskGroupMarketingGroupOccupancyMapper.class);
    }

    @AfterAll
    static void clearTenantContext() {
        TenantContext.clear();
    }

    @BeforeEach
    void resetRows() {
        jdbc.update("DELETE FROM pull_task_group_marketing_group_occupancy");
        TenantContext.set(7L);
    }

    @Test
    void migrationEnforcesOneActiveOccupancyPerTenantAndAllowsReuseAfterRelease() {
        PullTaskGroupMarketingGroupOccupancy first = occupancy("pool-a", 88L, 1_000L);
        PullTaskGroupMarketingGroupOccupancy conflict = occupancy("pool-b", 99L, 2_000L);

        assertThat(occupancyMapper.insertWaiting(first)).isEqualTo(1);
        assertThatThrownBy(() -> occupancyMapper.insertWaiting(conflict))
                .isInstanceOf(DuplicateKeyException.class);

        TenantContext.set(8L);
        assertThat(occupancyMapper.insertWaiting(conflict)).isEqualTo(1);

        TenantContext.set(7L);
        assertThat(occupancyMapper.releaseWaiting("pool-a", GROUP_JID, 88L, 3_000L))
                .isEqualTo(1);
        assertThat(occupancyMapper.insertWaiting(conflict)).isEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM pull_task_group_marketing_group_occupancy
                WHERE tenant_id = 7 AND group_jid = ? AND released_at IS NULL
                """, Integer.class, GROUP_JID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM pull_task_group_marketing_group_occupancy
                WHERE tenant_id = 7 AND group_jid = ? AND released_at IS NOT NULL
                  AND active_key IS NULL
                """, Integer.class, GROUP_JID)).isEqualTo(1);
    }

    @Test
    void concurrentInsertsHaveExactlyOneWinnerInRealInnoDb() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> insertConcurrently(
                    start, occupancy("pool-concurrent-a", 88L, 1_000L)));
            Future<Boolean> second = executor.submit(() -> insertConcurrently(
                    start, occupancy("pool-concurrent-b", 99L, 1_000L)));
            start.countDown();

            assertThat(List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(occupancyMapper.selectActiveByGroupJids(List.of(GROUP_JID)))
                    .singleElement();
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean insertConcurrently(
            CountDownLatch start,
            PullTaskGroupMarketingGroupOccupancy row) throws Exception {
        TenantContext.set(7L);
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent insert start timed out");
            }
            try {
                return occupancyMapper.insertWaiting(row) == 1;
            } catch (DuplicateKeyException expected) {
                return false;
            }
        } finally {
            TenantContext.clear();
        }
    }

    private static PullTaskGroupMarketingGroupOccupancy occupancy(
            String token,
            long createdBy,
            long now) {
        PullTaskGroupMarketingGroupOccupancy row = new PullTaskGroupMarketingGroupOccupancy();
        row.setGroupLinkId(1001L);
        row.setGroupJid(GROUP_JID);
        row.setGroupSource("HISTORICAL");
        row.setOccupancyType("WAITING");
        row.setReservationToken(token);
        row.setTaskNameSnapshot("MySQL端到端");
        row.setLastValidatedAt(now);
        row.setCreatedBy(createdBy);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setExpiresAt(now + 7_200_000L);
        return row;
    }

    private static String readMigration() throws Exception {
        try (var input = new ClassPathResource(
                "db/migration/V089__pull_task_group_marketing_group_occupancy.sql")
                .getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static SqlSessionTemplate buildSqlSessionTemplate(DataSource dataSource)
            throws Exception {
        MyBatisConfig tenantConfig = new MyBatisConfig();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setPlugins(tenantConfig.mybatisPlusInterceptor(
                tenantConfig.tenantLineHandler()));
        factoryBean.setMapperLocations(new ClassPathResource(
                "mapper/task/PullTaskGroupMarketingGroupOccupancyMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) {
            throw new IllegalStateException("MyBatis SqlSessionFactory was not created");
        }
        return new SqlSessionTemplate(factory);
    }
}
