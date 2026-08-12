package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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

/** 群组列表批量刷新任务明细 Mapper H2 MySQL 模式测试。 */
@SpringJUnitConfig(GroupBatchTaskItemMapperDbTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class GroupBatchTaskItemMapperDbTest {

    private static final long TENANT_ID = 7L;
    private static final long TASK_ID = 900L;
    private static final int PENDING = GroupBatchTaskItemStatus.PENDING.code();

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GroupBatchTaskItemMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        resetSchema();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void finishItemIsRejectedOnSecondCallSoRetriesCannotDoubleCountTheSummary() {
        mapper.batchInsert(List.of(item(101L), item(102L)));
        GroupBatchTaskItem target = mapper.selectPending(TASK_ID, PENDING, 10).get(0);

        GroupBatchTaskItem success = new GroupBatchTaskItem();
        success.setId(target.getId());
        success.setStatus(GroupBatchTaskItemStatus.SUCCESS.code());
        success.setAccountId(77L);
        success.setGroupJid("120363batch@g.us");
        success.setDescription("邀请链接已更新");
        success.setOperatedAt(2_000L);
        success.setUpdatedAt(2_000L);

        assertThat(mapper.finishItem(success, PENDING)).isEqualTo(1);
        // 执行器重入时必须拿到 0 行，否则同一项会被计入两次汇总，进度会超过总数。
        assertThat(mapper.finishItem(success, PENDING)).isZero();
    }

    @Test
    void selectPendingOnlyReturnsUnfinishedItemsInStableOrder() {
        mapper.batchInsert(List.of(item(101L), item(102L), item(103L)));
        GroupBatchTaskItem first = mapper.selectPending(TASK_ID, PENDING, 10).get(0);

        GroupBatchTaskItem failed = new GroupBatchTaskItem();
        failed.setId(first.getId());
        failed.setStatus(GroupBatchTaskItemStatus.FAILED.code());
        failed.setErrorCode("NO_AVAILABLE_ADMIN");
        failed.setDescription("系统内没有可用管理员账号");
        failed.setOperatedAt(3_000L);
        failed.setUpdatedAt(3_000L);
        mapper.finishItem(failed, PENDING);

        assertThat(mapper.selectPending(TASK_ID, PENDING, 10))
                .extracting(GroupBatchTaskItem::getGroupLinkId)
                .containsExactly(102L, 103L);
        assertThat(mapper.selectByTaskId(TASK_ID))
                .extracting(GroupBatchTaskItem::getGroupLinkId)
                .containsExactly(101L, 102L, 103L);
    }

    private static GroupBatchTaskItem item(long groupLinkId) {
        GroupBatchTaskItem row = new GroupBatchTaskItem();
        row.setTenantId(TENANT_ID);
        row.setTaskId(TASK_ID);
        row.setGroupLinkId(groupLinkId);
        row.setStatus(PENDING);
        row.setCreatedAt(1_000L);
        row.setUpdatedAt(1_000L);
        return row;
    }

    private void resetSchema() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE group_batch_task_item (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    task_id BIGINT NOT NULL,
                    group_link_id BIGINT NOT NULL,
                    group_jid VARCHAR(128),
                    account_id BIGINT,
                    status TINYINT NOT NULL,
                    error_code VARCHAR(64),
                    description VARCHAR(512),
                    baseline_synced_at BIGINT,
                    operated_at BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    CONSTRAINT uq_group_batch_task_item_group UNIQUE (task_id, group_link_id)
                )
                """);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** 本测试所需的最小 MyBatis 与租户拦截器配置。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:group_batch_task_item_mapper_test;"
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
            configuration.setUseGeneratedKeys(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource(
                    "mapper/group/GroupBatchTaskItemMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        GroupBatchTaskItemMapper groupBatchTaskItemMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupBatchTaskItemMapper.class);
        }
    }
}
