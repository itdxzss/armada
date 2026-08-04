package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTaskStandardGroupSetting;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 普通群链接任务群资料设置 Mapper 的 H2 测试。 */
@SpringJUnitConfig(PullTaskStandardGroupSettingMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStandardGroupSettingMapperInMemoryTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskStandardGroupSettingMapper mapper;

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
    void insertAndReadBackEveryGroupSettingColumn() {
        mapper.insert(sample(1L, "avatar-a.png"));

        PullTaskStandardGroupSetting saved = mapper.selectByTaskId(1L);
        assertThat(saved.getTenantId()).isEqualTo(7L);
        assertThat(saved.getTaskId()).isEqualTo(1L);
        assertThat(saved.getSettingTiming()).isEqualTo(1);
        assertThat(saved.getGroupName()).isEqualTo("客户群");
        assertThat(saved.getMaterialFilenameAsGroupName()).isZero();
        assertThat(saved.getAvatarFileKey()).isEqualTo("avatar-a.png");
        assertThat(saved.getGroupDescription()).isEqualTo("群说明");
        assertThat(saved.getAutoUnmuteAfterTask()).isEqualTo(1);
        assertThat(saved.getAutoCloseInviteAfterTask()).isEqualTo(1);
        assertThat(saved.getEditPermissionMode()).isEqualTo(2);
        assertThat(saved.getMuteMode()).isEqualTo(1);
        assertThat(saved.getLinkPermissionMode()).isEqualTo(2);
        assertThat(saved.getDisappearingMessageMode()).isEqualTo(3);
        assertThat(saved.getCreatedAt()).isEqualTo(100L);
        assertThat(saved.getUpdatedAt()).isEqualTo(100L);
    }

    @Test
    void permitsMultipleNullAvatarKeysButRejectsSameBoundKeyWithinTenant() {
        mapper.insert(sample(1L, null));
        mapper.insert(sample(2L, null));
        mapper.insert(sample(3L, "bound.png"));

        assertThatThrownBy(() -> mapper.insert(sample(4L, "bound.png")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void readByTaskIdIsTenantIsolated() {
        mapper.insert(sample(1L, "tenant-7.png"));

        TenantContext.set(8L);
        assertThat(mapper.selectByTaskId(1L)).isNull();
    }

    private PullTaskStandardGroupSetting sample(long taskId, String avatarKey) {
        PullTaskStandardGroupSetting row = new PullTaskStandardGroupSetting();
        row.setTaskId(taskId);
        row.setSettingTiming(1);
        row.setGroupName("客户群");
        row.setMaterialFilenameAsGroupName(0);
        row.setAvatarFileKey(avatarKey);
        row.setGroupDescription("群说明");
        row.setAutoUnmuteAfterTask(1);
        row.setAutoCloseInviteAfterTask(1);
        row.setEditPermissionMode(2);
        row.setMuteMode(1);
        row.setLinkPermissionMode(2);
        row.setDisappearingMessageMode(3);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_standard_group_setting_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskStandardGroupSettingMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskStandardGroupSettingMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardGroupSettingMapper.class);
        }
    }
}
