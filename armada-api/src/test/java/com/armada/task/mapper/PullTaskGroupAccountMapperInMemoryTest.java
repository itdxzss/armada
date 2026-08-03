package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.vo.PullTaskGroupAccountRoleCount;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 角色账号 Mapper 的 H2 MySQL 模式测试：拉手跨任务互斥、释放与重新占用。 */
@SpringJUnitConfig(PullTaskGroupAccountMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskGroupAccountMapperInMemoryTest {

    private static final long EXEC_A = 501L;
    private static final long EXEC_B = 502L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskGroupAccountMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void samePullerCannotServeTwoExecutionRowsAtOnce() {
        mapper.insert(role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1));

        // 另一个父任务的执行行想占同一个拉手账号：唯一键直接拒绝。
        assertThatThrownBy(() ->
                mapper.insert(role(200L, EXEC_B, 900L, PullTaskGroupAccountRole.PULLER, 1)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void releasingAPullerLetsAnotherTaskTakeIt() {
        PullTaskGroupAccount first = role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1);
        mapper.insert(first);

        assertThat(mapper.releasePuller(first.getId(), 800L)).isEqualTo(1);

        // 释放后 occupancy_key 变 NULL，不再参与唯一约束。
        mapper.insert(role(200L, EXEC_B, 900L, PullTaskGroupAccountRole.PULLER, 1));
        assertThat(mapper.selectByExecutionAndRole(EXEC_B, PullTaskGroupAccountRole.PULLER.code()))
                .hasSize(1);
    }

    @Test
    void reoccupyFailsWhenAnotherTaskAlreadyTookTheAccount() {
        PullTaskGroupAccount first = role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1);
        mapper.insert(first);
        mapper.releasePuller(first.getId(), 800L);
        mapper.insert(role(200L, EXEC_B, 900L, PullTaskGroupAccountRole.PULLER, 1));

        // 恢复执行时重新竞争拉手；已被别人占走就必须失败，让本行进入等待拉手。
        assertThatThrownBy(() -> mapper.reoccupyPuller(first.getId(), 850L))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void reoccupySucceedsWhenAccountIsStillFree() {
        PullTaskGroupAccount first = role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1);
        mapper.insert(first);
        mapper.releasePuller(first.getId(), 800L);

        assertThat(mapper.reoccupyPuller(first.getId(), 850L)).isEqualTo(1);
        assertThatThrownBy(() ->
                mapper.insert(role(200L, EXEC_B, 900L, PullTaskGroupAccountRole.PULLER, 1)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void managersAndStationsNeverOccupyAcrossExecutions() {
        mapper.insert(role(100L, EXEC_A, 910L, PullTaskGroupAccountRole.MANAGER, 1));
        mapper.insert(role(100L, EXEC_B, 910L, PullTaskGroupAccountRole.MANAGER, 1));
        mapper.insert(role(100L, EXEC_A, 920L, PullTaskGroupAccountRole.STATION, 1));
        mapper.insert(role(100L, EXEC_B, 920L, PullTaskGroupAccountRole.STATION, 1));

        // 管理账号要进每一条执行行；站台允许跨执行行复用。
        assertThat(mapper.selectByExecutionAndRole(EXEC_A, PullTaskGroupAccountRole.MANAGER.code()))
                .hasSize(1);
        assertThat(mapper.selectByExecutionAndRole(EXEC_B, PullTaskGroupAccountRole.STATION.code()))
                .hasSize(1);
    }

    @Test
    void sameStationCannotEnterTheSameExecutionTwice() {
        mapper.insert(role(100L, EXEC_A, 920L, PullTaskGroupAccountRole.STATION, 1));

        assertThatThrownBy(() ->
                mapper.insert(role(100L, EXEC_A, 920L, PullTaskGroupAccountRole.STATION, 2)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void releaseAllPullersOfExecutionFreesEveryActiveOccupation() {
        mapper.insert(role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1));
        mapper.insert(role(100L, EXEC_A, 901L, PullTaskGroupAccountRole.PULLER, 2));
        mapper.insert(role(100L, EXEC_A, 910L, PullTaskGroupAccountRole.MANAGER, 1));

        // 执行行暂停或进入资源等待时释放全部拉手，管理角色不受影响。
        assertThat(mapper.releaseAllPullersOfExecution(EXEC_A, 800L)).isEqualTo(2);
        mapper.insert(role(200L, EXEC_B, 900L, PullTaskGroupAccountRole.PULLER, 1));
        assertThat(mapper.selectByExecutionAndRole(EXEC_B, PullTaskGroupAccountRole.PULLER.code()))
                .hasSize(1);
    }

    @Test
    void availableCountIsComputedPerRole() {
        mapper.insert(role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1));
        PullTaskGroupAccount cooled = role(100L, EXEC_A, 901L, PullTaskGroupAccountRole.PULLER, 2);
        mapper.insert(cooled);
        mapper.insert(role(100L, EXEC_A, 910L, PullTaskGroupAccountRole.MANAGER, 1));

        mapper.markUnavailable(cooled.getId(),
                PullTaskGroupAccountAvailability.RISK_COOLDOWN.code(), "RISK", 5000L, 800L);

        List<PullTaskGroupAccountRoleCount> counts = mapper.countAvailableByRole(EXEC_A);
        assertThat(counts)
                .extracting(PullTaskGroupAccountRoleCount::getRoleType,
                            PullTaskGroupAccountRoleCount::getAvailableCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1, 1),
                        org.assertj.core.groups.Tuple.tuple(2, 1));
    }

    @Test
    void updateMembershipRecordsJoinResult() {
        PullTaskGroupAccount manager = role(100L, EXEC_A, 910L, PullTaskGroupAccountRole.MANAGER, 1);
        mapper.insert(manager);

        // joinedAt 与 now 故意取不同值：若 XML 把两个字段的绑定顺序写反，这里能测出来。
        mapper.updateMembership(manager.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 950L, 960L);

        PullTaskGroupAccount saved =
                mapper.selectByExecutionAndRole(EXEC_A, PullTaskGroupAccountRole.MANAGER.code()).get(0);
        assertThat(saved.getMembershipStatus())
                .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        assertThat(saved.getJoinedAt()).isEqualTo(950L);
        assertThat(saved.getUpdatedAt()).isEqualTo(960L);
    }

    @Test
    void otherTenantRoleRowsAreInvisible() {
        mapper.insert(role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1));

        TenantContext.set(8L);
        assertThat(mapper.selectByExecutionAndRole(EXEC_A, PullTaskGroupAccountRole.PULLER.code()))
                .isEmpty();
        assertThat(mapper.releaseAllPullersOfExecution(EXEC_A, 800L)).isZero();
    }

    @Test
    void insertPersistsEveryScalarColumnAtItsOwnDistinctValue() {
        // role_seq/source_type/selection_mode/entry_mode 都是相邻小整数,
        // occupied_at/created_at/updated_at 都是相邻 BIGINT——全部取互不相同的值,
        // 这样 INSERT 列清单和 VALUES 清单一旦错位绑定,断言就会失败。
        PullTaskGroupAccount puller = role(100L, EXEC_A, 900L, PullTaskGroupAccountRole.PULLER, 1);
        mapper.insert(puller);

        PullTaskGroupAccount saved =
                mapper.selectByExecutionAndRole(EXEC_A, PullTaskGroupAccountRole.PULLER.code()).get(0);
        assertThat(saved.getTaskId()).isEqualTo(100L);
        assertThat(saved.getGroupExecutionId()).isEqualTo(EXEC_A);
        assertThat(saved.getAccountId()).isEqualTo(900L);
        assertThat(saved.getAccountPhone()).isEqualTo("86138900");
        assertThat(saved.getRoleType()).isEqualTo(PullTaskGroupAccountRole.PULLER.code());
        assertThat(saved.getRoleSeq()).isEqualTo(1);
        assertThat(saved.getSourceType()).isEqualTo(2);
        assertThat(saved.getSelectionMode()).isEqualTo(3);
        assertThat(saved.getEntryMode()).isEqualTo(4);
        assertThat(saved.getMembershipStatus())
                .isEqualTo(PullTaskGroupAccountMembershipStatus.NOT_JOINED.code());
        assertThat(saved.getAdminStatus()).isEqualTo(0);
        assertThat(saved.getAvailabilityStatus())
                .isEqualTo(PullTaskGroupAccountAvailability.AVAILABLE.code());
        assertThat(saved.getCreatedAt()).isEqualTo(100L);
        assertThat(saved.getUpdatedAt()).isEqualTo(150L);
        assertThat(saved.getOccupiedAt()).isEqualTo(175L);
        assertThat(saved.getReleasedAt()).isNull();
    }

    private PullTaskGroupAccount role(long taskId, long executionId, long accountId,
                                      PullTaskGroupAccountRole roleType, int roleSeq) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(taskId);
        row.setGroupExecutionId(executionId);
        row.setAccountId(accountId);
        row.setAccountPhone("86138" + accountId);
        row.setRoleType(roleType.code());
        row.setRoleSeq(roleSeq);
        // source_type/selection_mode/entry_mode 故意取互不相同、且与 roleSeq 不同的值,
        // 防止相邻小整数列在 XML 里被误绑到彼此仍然测不出来。
        row.setSourceType(2);
        row.setSelectionMode(3);
        row.setEntryMode(roleType == PullTaskGroupAccountRole.STATION ? null : 4);
        // created_at/updated_at/occupied_at 同理取互不相同的值。
        row.setCreatedAt(100L);
        row.setUpdatedAt(150L);
        if (roleType == PullTaskGroupAccountRole.PULLER) {
            row.setOccupiedAt(175L);
        }
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_group_account_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskGroupAccountMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskGroupAccountMapper pullTaskGroupAccountMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskGroupAccountMapper.class);
        }
    }
}
