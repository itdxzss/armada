package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.mapper.GroupCurrentInviteMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.impl.GroupCurrentInvitePersistence;
import com.armada.group.service.impl.GroupInviteLinkServiceImpl;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskManagerJoinPayload;
import com.armada.task.model.dto.PullTaskManagerJoinWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 真实群绑定服务、Mapper、租户插件与事务覆盖后台恢复线程丢失租户的事故。 */
class PullTaskManagerJoinTenantH2Test {

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private GroupInviteLinkServiceImpl inviteService;
    private PullTaskManagerJoinProtocolExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.clear();
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:manager_join_tenant;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE group_link (id BIGINT PRIMARY KEY, tenant_id BIGINT, "
                + "group_id BIGINT, group_invite_id BIGINT, deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE wa_group (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT, "
                + "group_jid VARCHAR(128), deleted_at BIGINT, display_name VARCHAR(128), origin INT, "
                + "created_at BIGINT, updated_at BIGINT, UNIQUE(tenant_id,group_jid))");
        jdbc.execute("CREATE TABLE wa_group_profile (id BIGINT PRIMARY KEY, tenant_id BIGINT, "
                + "group_id BIGINT, current_invite_id BIGINT)");
        jdbc.execute("CREATE TABLE wa_group_invite (id BIGINT PRIMARY KEY, tenant_id BIGINT, "
                + "invite_code VARCHAR(128), deleted_at BIGINT)");
        jdbc.update("INSERT INTO group_link VALUES (51,7,NULL,NULL,NULL),(52,8,NULL,NULL,NULL)");
        // 同一 WhatsApp 群在两个租户中有不同内部 ID，能暴露错误租户下的绑定。
        jdbc.update("INSERT INTO wa_group (id,tenant_id,group_jid,deleted_at) VALUES (71,7,'120363group@g.us',NULL),"
                + "(81,8,'120363group@g.us',NULL)");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        MyBatisConfig production = new MyBatisConfig();
        factory.setPlugins(production.mybatisPlusInterceptor(production.tenantLineHandler()));
        String inviteXml;
        try (var stream = new ClassPathResource("mapper/group/GroupCurrentInviteMapper.xml")
                .getInputStream()) {
            inviteXml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        // H2 不识别 MySQL 的索引提示；仅删除提示，保留真实 SQL 条件及 FOR UPDATE。
        // 本测试验证租户和绑定事务，不将 H2 当作 InnoDB 锁行为验证。
        factory.setMapperLocations(
                new ClassPathResource("mapper/group/GroupLinkMapper.xml"),
                new ByteArrayResource(inviteXml.replace(" FORCE INDEX (PRIMARY)", "")
                        .getBytes(StandardCharsets.UTF_8)));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        transactionManager = new DataSourceTransactionManager(dataSource);
        GroupCurrentInvitePersistence persistence = transactional(new GroupCurrentInvitePersistence(
                session.getMapper(GroupCurrentInviteMapper.class)));
        inviteService = transactional(new GroupInviteLinkServiceImpl(
                mock(GroupLinkRegistryService.class), session.getMapper(GroupLinkMapper.class),
                mock(GroupExecutionAccountSelector.class), mock(GroupInvitePort.class), persistence));
        GroupJoinPort port = mock(GroupJoinPort.class);
        when(port.join(org.mockito.ArgumentMatchers.any())).thenReturn(
                new GroupJoinResult("120363group@g.us", GroupJoinOutcome.JOINED));
        executor = new PullTaskManagerJoinProtocolExecutor(port, inviteService);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void backgroundRecoveryBindsConfirmedGroupWithoutHttpTenant() {
        assertThat(executor.join(candidate(51L), work(7L)))
                .isEqualTo(PullTaskManagerJoinOutcome.confirmed("120363group@g.us"));

        assertThat(groupId(51L)).isEqualTo(71L);
        assertThat(groupId(52L)).isNull();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void reusedThreadBindsEachTenantOwnGroupAndRestoresCaller() {
        TenantContext.set(99L);
        executor.join(candidate(51L), work(7L));
        assertThat(TenantContext.get()).isEqualTo(99L);
        executor.join(candidate(52L), work(8L));

        assertThat(groupId(51L)).isEqualTo(71L);
        assertThat(groupId(52L)).isEqualTo(81L);
        assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void persistenceGuardStillRejectsMissingTenant() {
        assertThatThrownBy(() -> inviteService.bindGroupJid(51L, "120363group@g.us", 1_000L))
                .isInstanceOf(BusinessException.class);
        assertThat(groupId(51L)).isNull();
    }

    private Long groupId(long linkId) {
        return jdbc.queryForObject("SELECT group_id FROM group_link WHERE id=?", Long.class, linkId);
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(interceptor);
        return (T) proxy.getProxy();
    }

    private static PullTaskGroupExecution candidate(long linkId) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setGroupLinkId(linkId);
        return row;
    }

    private static PullTaskManagerJoinWork work(long tenantId) {
        ProtocolAccountRef account = new ProtocolAccountRef(
                901L, ProtocolBackend.ANDROID, "acc-901", "8613800000901");
        return new PullTaskManagerJoinWork(tenantId, 11L, 501L, 601L,
                new PullTaskManagerJoinPayload(account, "InviteCode", "pull-task-manager-join:601",
                        "worker-1", 2));
    }
}
