package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
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

/** 料子成员 Mapper 的 H2 MySQL 模式测试：游标语义、单文件去重与回调定位。 */
@SpringJUnitConfig(PullTaskMaterialMemberMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskMaterialMemberMapperInMemoryTest {

    private static final long EXECUTION = 500L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskMaterialMemberMapper mapper;

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
    void batchInsertPersistsAllMembersWithStableOrder() {
        mapper.batchInsert(List.of(
                member(1, "8613800000001", 0),
                member(2, "8613800000002", 1),
                member(3, "8613800000003", 0)));

        List<PullTaskMaterialMember> unconsumed = mapper.selectUnconsumed(EXECUTION, 10);
        assertThat(unconsumed).extracting(PullTaskMaterialMember::getMemberSeq)
                .containsExactly(1, 2, 3);
        assertThat(unconsumed.get(1).getAdminRequired()).isEqualTo(1);
        // sourceLineNo 与 normalizedPhone 各自回读，避免 INSERT 列清单里位置错位。
        // sourceLineNo 用 seq * 10 + 3 构造，与 memberSeq 恒不相等：两者是相邻同类型的
        // INT 列，若 fixture 让它们总是相等，二者互换也能"回读正确"，测试就失去了意义。
        assertThat(unconsumed.get(1).getMemberSeq()).isEqualTo(2);
        assertThat(unconsumed.get(1).getSourceLineNo()).isEqualTo(23);
        assertThat(unconsumed.get(1).getNormalizedPhone()).isEqualTo("8613800000002");
    }

    @Test
    void duplicatePhoneWithinOneExecutionIsRejected() {
        mapper.batchInsert(List.of(member(1, "8613800000001", 0)));

        assertThatThrownBy(() -> mapper.batchInsert(List.of(member(2, "8613800000001", 0))))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void assignToCallConsumesMembersAndAdvancesTheCursor() {
        mapper.batchInsert(List.of(
                member(1, "8613800000001", 0),
                member(2, "8613800000002", 0),
                member(3, "8613800000003", 0)));

        List<Long> firstBatch = mapper.selectUnconsumed(EXECUTION, 2).stream()
                .map(PullTaskMaterialMember::getId).toList();
        assertThat(mapper.assignToCall(firstBatch, 900L, 900L)).isEqualTo(2);

        // pull_call_id 非空即"已消费"，游标自然前移，不需要单独的游标列。
        assertThat(mapper.selectUnconsumed(EXECUTION, 10))
                .extracting(PullTaskMaterialMember::getMemberSeq)
                .containsExactly(3);
    }

    @Test
    void alreadyConsumedMembersAreNotReassigned() {
        mapper.batchInsert(List.of(member(1, "8613800000001", 0)));
        Long id = mapper.selectUnconsumed(EXECUTION, 1).get(0).getId();

        assertThat(mapper.assignToCall(List.of(id), 900L, 900L)).isEqualTo(1);
        // 重复分配必须是 0 行：一个料子一生只属于一次调用。
        assertThat(mapper.assignToCall(List.of(id), 901L, 901L)).isZero();
    }

    @Test
    void pullResultWriteBackKeepsUnknownDistinctFromFailure() {
        mapper.batchInsert(List.of(
                member(1, "8613800000001", 0),
                member(2, "8613800000002", 0)));
        List<PullTaskMaterialMember> rows = mapper.selectUnconsumed(EXECUTION, 10);
        mapper.assignToCall(rows.stream().map(PullTaskMaterialMember::getId).toList(), 900L, 900L);

        mapper.writeBackPullResult(rows.get(0).getId(),
                PullTaskMaterialPullStatus.SUCCESS.code(), null, null, "8613800000001@s.whatsapp.net", 950L);
        mapper.writeBackPullResult(rows.get(1).getId(),
                PullTaskMaterialPullStatus.UNKNOWN.code(), "TIMEOUT", "协议超时", null, 950L);

        List<PullTaskMaterialMember> after = mapper.selectByExecution(EXECUTION);
        assertThat(after.get(0).getPullStatus()).isEqualTo(PullTaskMaterialPullStatus.SUCCESS.code());
        assertThat(after.get(0).getWaJid()).isEqualTo("8613800000001@s.whatsapp.net");
        // 成功行的原因码/原因描述必须保持 null，不能被另一行的字段串位。
        assertThat(after.get(0).getPullReasonCode()).isNull();
        assertThat(after.get(0).getPullReasonMessage()).isNull();
        assertThat(after.get(0).getPullResultAt()).isEqualTo(950L);

        assertThat(after.get(1).getPullStatus()).isEqualTo(PullTaskMaterialPullStatus.UNKNOWN.code());
        assertThat(after.get(1).getPullReasonCode()).isEqualTo("TIMEOUT");
        // reasonMessage 与 waJid 分别断言，避免二者在 UPDATE 里被写反。
        assertThat(after.get(1).getPullReasonMessage()).isEqualTo("协议超时");
        assertThat(after.get(1).getWaJid()).isNull();
        assertThat(after.get(1).getPullResultAt()).isEqualTo(950L);
    }

    @Test
    void pendingAdminOnlyIncludesFlaggedMembersThatJoinedSuccessfully() {
        mapper.batchInsert(List.of(
                member(1, "8613800000001", 1),
                member(2, "8613800000002", 1),
                member(3, "8613800000003", 0)));
        List<PullTaskMaterialMember> rows = mapper.selectUnconsumed(EXECUTION, 10);
        mapper.assignToCall(rows.stream().map(PullTaskMaterialMember::getId).toList(), 900L, 900L);

        mapper.writeBackPullResult(rows.get(0).getId(),
                PullTaskMaterialPullStatus.SUCCESS.code(), null, null, "jid1", 950L);
        mapper.writeBackPullResult(rows.get(1).getId(),
                PullTaskMaterialPullStatus.FAILED.code(), "PRIVACY", "隐私限制", null, 950L);
        mapper.writeBackPullResult(rows.get(2).getId(),
                PullTaskMaterialPullStatus.SUCCESS.code(), null, null, "jid3", 950L);

        // 入群失败或结果未知的标记料子不提权；未标记的成功料子也不提权。
        assertThat(mapper.selectPendingAdmin(EXECUTION))
                .extracting(PullTaskMaterialMember::getNormalizedPhone)
                .containsExactly("8613800000001");
    }

    @Test
    void adminCallbackIsLocatedByCommandId() {
        mapper.batchInsert(List.of(member(1, "8613800000001", 1)));
        Long id = mapper.selectUnconsumed(EXECUTION, 1).get(0).getId();
        mapper.assignToCall(List.of(id), 900L, 900L);
        mapper.writeBackPullResult(id, PullTaskMaterialPullStatus.SUCCESS.code(), null, null, "jid1", 950L);

        mapper.markAdminSubmitted(id, "cmd-admin-1", 960L);

        PullTaskMaterialMember found = mapper.selectByAdminCommandId("cmd-admin-1");
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getAdminStatus())
                .isEqualTo(PullTaskMaterialAdminStatus.SUBMITTED.code());
        // 命令 ID 本身也要原样回读，避免和 pull 结果块的字段串位。
        assertThat(found.getAdminCommandId()).isEqualTo("cmd-admin-1");
    }

    @Test
    void otherTenantMembersAreInvisible() {
        mapper.batchInsert(List.of(member(1, "8613800000001", 0)));

        TenantContext.set(8L);
        assertThat(mapper.selectUnconsumed(EXECUTION, 10)).isEmpty();
        assertThat(mapper.selectByAdminCommandId("cmd-admin-1")).isNull();
    }

    private PullTaskMaterialMember member(int seq, String phone, int adminRequired) {
        PullTaskMaterialMember row = new PullTaskMaterialMember();
        row.setGroupExecutionId(EXECUTION);
        row.setMemberSeq(seq);
        // 与 seq 保持非线性、恒不相等的关系：真实数据里 sourceLineNo(首次出现的原始行号)
        // 一旦文件里有空行/非法号码/重复号码就会与 memberSeq(去重后顺序)分道扬镳；
        // 若 fixture 让两者恒等，INSERT 列清单里这两个相邻 INT 列的换位 bug 就无法被
        // 任何断言捕获。
        row.setSourceLineNo(seq * 10 + 3);
        row.setNormalizedPhone(phone);
        row.setAdminRequired(adminRequired);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_material_member_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskMaterialMemberMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskMaterialMemberMapper pullTaskMaterialMemberMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskMaterialMemberMapper.class);
        }
    }
}
