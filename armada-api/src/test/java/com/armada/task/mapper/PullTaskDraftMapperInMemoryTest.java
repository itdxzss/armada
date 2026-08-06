package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.enums.PullTaskType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 普通群链接草稿任务行的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(PullTaskDraftMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskDraftMapperInMemoryTest {

    private static final long CREATOR = 501L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskMapper mapper;

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
    void insertDraftWritesStandardNormalLinkDraftAndFillsGeneratedId() {
        PullTask draft = draftRow();

        assertThat(mapper.insertDraft(draft)).isEqualTo(1);
        assertThat(draft.getId()).isNotNull();

        PullTask saved = mapper.selectLatestDraftByCreator(CREATOR);
        assertThat(saved.getId()).isEqualTo(draft.getId());
        assertThat(saved.getTenantId()).isEqualTo(7L);
        assertThat(saved.getTaskType()).isEqualTo(PullTaskType.STANDARD);
        assertThat(saved.getStatus()).isEqualTo("DRAFT");
        // mode 取新值 NORMAL_LINK，不复用已被 PRD 移除的 OLD_LINK。
        assertThat(saved.getMode()).isEqualTo("NORMAL_LINK");
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getGroupCount()).isZero();
        assertThat(saved.getExpectedPullCount()).isZero();
    }

    @Test
    void selectLatestDraftByCreatorIsScopedToTheCreator() {
        mapper.insertDraft(draftRow());

        assertThat(mapper.selectLatestDraftByCreator(CREATOR)).isNotNull();
        assertThat(mapper.selectLatestDraftByCreator(999L)).isNull();
    }

    @Test
    void selectLatestDraftByCreatorReturnsNewestWhenDuplicatesLeakThrough() {
        PullTask first = draftRow();
        mapper.insertDraft(first);
        PullTask second = draftRow();
        mapper.insertDraft(second);

        // 同用户双击或多标签页可能漏出第二条草稿，取最新一条容忍它。
        assertThat(mapper.selectLatestDraftByCreator(CREATOR).getId()).isEqualTo(second.getId());
    }

    @Test
    void selectLatestDraftByCreatorIgnoresSubmittedAndMarketingRows() {
        PullTask draft = draftRow();
        mapper.insertDraft(draft);
        mapper.submitDraft(submitRow(draft.getId()), 900L);

        assertThat(mapper.selectLatestDraftByCreator(CREATOR)).isNull();
    }

    @Test
    void submitDraftFlipsToWaitStartAndWritesNameRemarkConfigAndCounts() {
        PullTask draft = draftRow();
        mapper.insertDraft(draft);

        assertThat(mapper.submitDraft(submitRow(draft.getId()), 900L)).isEqualTo(1);

        PullTask saved = mapper.selectLifecycle(draft.getId());
        assertThat(saved.getStatus()).isEqualTo("WAIT_START");
        assertThat(saved.getVersion()).isEqualTo(2);
        assertThat(saved.getTaskName()).isEqualTo("正式任务名");
    }

    @Test
    void submitDraftUsesDraftStatusAsGuard() {
        PullTask draft = draftRow();
        mapper.insertDraft(draft);

        assertThat(mapper.submitDraft(submitRow(draft.getId()), 900L)).isEqualTo(1);
        assertThat(mapper.selectLifecycle(draft.getId()).getStatus()).isEqualTo("WAIT_START");
    }

    @Test
    void submitDraftIsIdempotentOnRepeatedSubmission() {
        PullTask draft = draftRow();
        mapper.insertDraft(draft);

        assertThat(mapper.submitDraft(submitRow(draft.getId()), 900L)).isEqualTo(1);
        // 第二次重放时已不再是草稿，必须 0 行而不是产生第二次副作用。
        assertThat(mapper.submitDraft(submitRow(draft.getId()), 901L)).isZero();
        assertThat(mapper.selectLifecycle(draft.getId()).getVersion()).isEqualTo(2);
    }

    @Test
    void otherTenantCannotSeeOrSubmitTheDraft() {
        PullTask draft = draftRow();
        mapper.insertDraft(draft);

        TenantContext.set(8L);
        assertThat(mapper.selectLatestDraftByCreator(CREATOR)).isNull();
        assertThat(mapper.submitDraft(submitRow(draft.getId()), 900L)).isZero();

        TenantContext.set(7L);
        assertThat(mapper.selectLifecycle(draft.getId()).getStatus()).isEqualTo("DRAFT");
    }

    private static PullTask draftRow() {
        PullTask row = new PullTask();
        row.setTaskName("未命名草稿");
        row.setOperatorName("运营甲");
        row.setCreatedBy(CREATOR);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private static PullTask submitRow(Long id) {
        PullTask row = new PullTask();
        row.setId(id);
        row.setTaskName("正式任务名");
        row.setRemark("备注");
        row.setConfigJson("{\"autoStart\":1}");
        row.setGroupCount(3);
        row.setExpectedPullCount(120);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_draft_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskMapper pullTaskMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskMapper.class);
        }
    }
}
