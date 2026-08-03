package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTaskStandardSetting;
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

/** 普通群链接冻结配置 Mapper 的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(PullTaskStandardSettingMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStandardSettingMapperInMemoryTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskStandardSettingMapper mapper;

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
    void insertAndReadBackAllFrozenConfig() {
        mapper.insert(sample(1L));

        PullTaskStandardSetting saved = mapper.selectByTaskId(1L);
        assertThat(saved.getTenantId()).isEqualTo(7L);
        assertThat(saved.getPullCountMin()).isEqualTo(3);
        assertThat(saved.getPullCountMax()).isEqualTo(8);
        assertThat(saved.getStationCountPerCall()).isEqualTo(2);
        assertThat(saved.getConcurrentGroupCount()).isEqualTo(1);
        assertThat(saved.getMaterialAdminTiming()).isEqualTo(1);
        assertThat(saved.getManagerGroupName()).isEqualTo("管理组");
        // 启动前 N 尚未冻结。
        assertThat(saved.getRequiredManagerCount()).isZero();
    }

    @Test
    void freezeRequiredManagerCountWritesTaskLevelN() {
        mapper.insert(sample(1L));

        assertThat(mapper.freezeRequiredManagerCount(1L, 4, 900L)).isEqualTo(1);

        PullTaskStandardSetting saved = mapper.selectByTaskId(1L);
        // N 冻结在任务级而不是执行行级:执行行受并发槽位控制、启动时刻不同,
        // 逐行冻结会得到互不相同的 N,导致各群缺口口径不一致。
        assertThat(saved.getRequiredManagerCount()).isEqualTo(4);
        assertThat(saved.getUpdatedAt()).isEqualTo(900L);
    }

    @Test
    void otherTenantSettingIsInvisibleAndUnwritable() {
        mapper.insert(sample(1L));

        TenantContext.set(8L);
        assertThat(mapper.selectByTaskId(1L)).isNull();
        assertThat(mapper.freezeRequiredManagerCount(1L, 9, 900L)).isZero();

        TenantContext.set(7L);
        assertThat(mapper.selectByTaskId(1L).getRequiredManagerCount()).isZero();
    }

    private PullTaskStandardSetting sample(long taskId) {
        PullTaskStandardSetting row = new PullTaskStandardSetting();
        row.setTaskId(taskId);
        row.setAutoStart(0);
        row.setMaterialAdminTiming(1);
        row.setPullCountMin(3);
        row.setPullCountMax(8);
        row.setPullIntervalSeconds(30);
        row.setPullerCountPerGroup(2);
        row.setStationCountPerCall(2);
        row.setConcurrentGroupCount(1);
        row.setPullerRiskMinutes(0);
        row.setRequiredManagerCount(0);
        row.setManagerGroupId(11L);
        row.setPullerGroupId(12L);
        row.setStationGroupId(13L);
        row.setManagerGroupName("管理组");
        row.setPullerGroupName("拉手组");
        row.setStationGroupName("站台组");
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_standard_setting_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskStandardSettingMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskStandardSettingMapper pullTaskStandardSettingMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskStandardSettingMapper.class);
        }
    }
}
