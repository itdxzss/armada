package com.armada.account.smsregistration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.account.mapper.SmsRegistrationMapper;
import com.armada.account.model.entity.SmsRegistration;
import com.armada.account.model.enums.SmsRegistrationState;
import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 在 H2 MySQL 模式执行真实迁移 V223、Mapper XML 与租户插件。 */
@SpringJUnitConfig(SmsRegistrationMapperH2Test.Config.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class SmsRegistrationMapperH2Test {
    @Autowired DataSource dataSource;
    @Autowired SmsRegistrationMapper mapper;

    @BeforeEach
    void schema() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute(new ClassPathResource("db/migration/V223__sms_registration.sql")
                    .getContentAsString(StandardCharsets.UTF_8));
        }
    }

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test
    void oneRegistrationPerOrderAndRowsAreTenantScoped() {
        TenantContext.set(7L);
        var row = row(100L, SmsRegistrationState.REQUESTING);
        mapper.insert(row);
        assertThat(row.getId()).isNotNull();
        assertThat(mapper.findByActivation(100L).getTenantId()).isEqualTo(7L);
        // 一个订单最多一条办理记录，重复点击靠唯一键兜底。
        assertThatThrownBy(() -> mapper.insert(row(100L, SmsRegistrationState.REQUESTING)))
                .isInstanceOf(DuplicateKeyException.class);

        TenantContext.set(8L);
        assertThat(mapper.findByActivation(100L)).isNull();
        assertThat(mapper.countActiveByActivation(100L)).isZero();
        assertThat(mapper.listByActivationIds(List.of(100L))).isEmpty();
    }

    @Test
    void transitionIsCompareAndSetAndWritesTransactionAndVersionOnlyOnce() {
        TenantContext.set(7L);
        var saved = row(100L, SmsRegistrationState.REQUESTING);
        mapper.insert(saved);

        var sent = mapper.findByActivation(100L);
        sent.setState(SmsRegistrationState.WAITING_CODE.code());
        sent.setGoTransactionId("tx-1");
        sent.setGoVersion(3L);
        sent.setFailureCode("");
        sent.setUpdatedAt(200L);
        assertThat(mapper.transition(sent, SmsRegistrationState.REQUESTING.code())).isEqualTo(1);

        // 迟到的重复推进仍以申请中为前提，不能把等码改回去，也不能覆盖事务与版本。
        var stale = mapper.findByActivation(100L);
        stale.setState(SmsRegistrationState.ACTION_REQUIRED.code());
        stale.setFailureCode("GO_REJECTED");
        assertThat(mapper.transition(stale, SmsRegistrationState.REQUESTING.code())).isZero();

        var verifying = mapper.findByActivation(100L);
        verifying.setState(SmsRegistrationState.VERIFYING.code());
        verifying.setGoTransactionId("tx-other");
        verifying.setGoVersion(99L);
        verifying.setFailureCode("");
        assertThat(mapper.transition(verifying, SmsRegistrationState.WAITING_CODE.code())).isEqualTo(1);
        var after = mapper.findByActivation(100L);
        assertThat(after.getGoTransactionId()).isEqualTo("tx-1");
        assertThat(after.getGoVersion()).isEqualTo(3L);
    }

    @Test
    void activeMeansRequestingWaitingOrVerifyingAndListActiveScansAllTenants() {
        TenantContext.set(7L);
        mapper.insert(row(1L, SmsRegistrationState.REQUESTING));
        mapper.insert(row(2L, SmsRegistrationState.WAITING_CODE));
        mapper.insert(row(3L, SmsRegistrationState.VERIFYING));
        mapper.insert(row(4L, SmsRegistrationState.REGISTERED));
        mapper.insert(row(5L, SmsRegistrationState.ACTION_REQUIRED));
        TenantContext.set(8L);
        mapper.insert(row(6L, SmsRegistrationState.WAITING_CODE));

        TenantContext.set(7L);
        assertThat(mapper.countActiveByActivation(1L)).isOne();
        assertThat(mapper.countActiveByActivation(4L)).isZero();
        assertThat(mapper.countActiveByActivation(5L)).isZero();
        assertThat(mapper.listByActivationIds(List.of(1L, 4L, 6L))).extracting(SmsRegistration::getSmsActivationId)
                .containsExactlyInAnyOrder(1L, 4L);

        // worker 没有租户上下文也要能扫到所有租户的进行中记录，且结束的状态不会被取出。
        TenantContext.clear();
        assertThat(mapper.listActive(10)).extracting(SmsRegistration::getSmsActivationId)
                .containsExactly(1L, 2L, 3L, 6L);
        assertThat(mapper.listActive(2)).hasSize(2);
        assertThat(mapper.listActive(10)).extracting(SmsRegistration::getTenantId).contains(7L, 8L);
    }

    @Test
    void pendingReleaseScansOnlyFinishedRowsWithProxyAcrossTenants() {
        TenantContext.set(7L);
        mapper.insert(row(1L, SmsRegistrationState.REQUESTING));
        mapper.insert(row(2L, SmsRegistrationState.WAITING_CODE));
        mapper.insert(row(3L, SmsRegistrationState.VERIFYING));
        mapper.insert(row(4L, SmsRegistrationState.REGISTERED));
        var released = row(5L, SmsRegistrationState.ACTION_REQUIRED);
        released.setProxyId(null);
        mapper.insert(released);
        TenantContext.set(8L);
        mapper.insert(row(6L, SmsRegistrationState.ACTION_REQUIRED));

        TenantContext.clear();
        assertThat(mapper.listPendingProxyRelease(SmsRegistrationState.finishedCodes(), 10))
                .extracting(SmsRegistration::getSmsActivationId).containsExactly(4L, 6L);
        assertThat(mapper.listPendingProxyRelease(SmsRegistrationState.finishedCodes(), 1))
                .extracting(SmsRegistration::getSmsActivationId).containsExactly(4L);
    }

    @Test
    void clearingReleasedProxyRequiresMatchingTenantProxyAndFinishedState() {
        TenantContext.set(7L);
        var finished = row(1L, SmsRegistrationState.REGISTERED);
        var active = row(2L, SmsRegistrationState.VERIFYING);
        mapper.insert(finished);
        mapper.insert(active);
        List<Integer> states = SmsRegistrationState.finishedCodes();

        TenantContext.set(8L);
        assertThat(mapper.clearReleasedProxy(finished.getId(), 5L, states)).isZero();
        TenantContext.set(7L);
        assertThat(mapper.clearReleasedProxy(finished.getId(), 6L, states)).isZero();
        assertThat(mapper.clearReleasedProxy(active.getId(), 5L, states)).isZero();
        assertThat(mapper.findByActivation(1L).getProxyId()).isEqualTo(5L);
        assertThat(mapper.findByActivation(2L).getProxyId()).isEqualTo(5L);

        assertThat(mapper.clearReleasedProxy(finished.getId(), 5L, states)).isOne();
        assertThat(mapper.clearReleasedProxy(finished.getId(), 5L, states)).isZero();
        var after = mapper.findByActivation(1L);
        assertThat(after.getProxyId()).isNull();
        assertThat(after.getState()).isEqualTo(SmsRegistrationState.REGISTERED.code());
        assertThat(after.getUpdatedAt()).isEqualTo(finished.getUpdatedAt());
        assertThat(mapper.listPendingProxyRelease(states, 10)).isEmpty();
    }

    private static SmsRegistration row(long activationId, SmsRegistrationState state) {
        var row = new SmsRegistration();
        row.setSmsActivationId(activationId);
        row.setState(state.code());
        row.setGoProfileId(42L);
        row.setProxyId(5L);
        row.setFailureCode("");
        row.setCreatedBy(1L);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    @Configuration @Import(MyBatisConfig.class)
    static class Config {
        @Bean DataSource dataSource() {
            var source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:sms_registration_mapper;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUser("sa"); source.setPassword(""); return source;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source, MybatisPlusInterceptor interceptor) throws Exception {
            var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource("mapper/account/SmsRegistrationMapper.xml"));
            return factory.getObject();
        }
        @Bean SmsRegistrationMapper mapper(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(SmsRegistrationMapper.class);
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
    }
}
