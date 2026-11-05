package com.armada.account.smsactivation;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.mapper.SmsActivationMapper;
import com.armada.account.model.dto.SmsActivationQuery;
import com.armada.account.model.entity.SmsActivation;
import com.armada.account.model.enums.SmsActivationState;
import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

/** 在 H2 MySQL 模式执行真实迁移、Mapper XML 与租户插件。 */
@SpringJUnitConfig(SmsActivationMapperH2Test.Config.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class SmsActivationMapperH2Test {
    @Autowired DataSource dataSource;
    @Autowired SmsActivationMapper mapper;

    @BeforeEach
    void schema() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute(new ClassPathResource("db/migration/V221__sms_activation.sql")
                    .getContentAsString(StandardCharsets.UTF_8));
        }
    }

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test
    void rowsAreTenantScopedAndPagedNewestFirst() {
        TenantContext.set(7L);
        var first = row("a1", 100L);
        var second = row("a2", 200L);
        mapper.insert(first);
        mapper.insert(second);
        assertThat(first.getId()).isNotNull();
        assertThat(mapper.find(first.getId()).getTenantId()).isEqualTo(7L);

        var query = new SmsActivationQuery();
        query.setPageSize(1);
        assertThat(mapper.count()).isEqualTo(2);
        assertThat(mapper.list(query)).extracting(SmsActivation::getActivationId).containsExactly("a2");

        TenantContext.set(8L);
        assertThat(mapper.find(first.getId())).isNull();
        assertThat(mapper.count()).isZero();
        first.setState(SmsActivationState.CANCELLED.code());
        assertThat(mapper.transition(first, SmsActivationState.WAITING_CODE.code())).isZero();
    }

    @Test
    void transitionOnlyFromExpectedStateAndKeepsCode() {
        TenantContext.set(7L);
        var saved = row("a1", 100L);
        mapper.insert(saved);

        var received = mapper.find(saved.getId());
        received.setState(SmsActivationState.RECEIVED.code());
        received.setSmsCode("004271");
        received.setReceivedAt(150L);
        received.setUpdatedAt(150L);
        assertThat(mapper.transition(received, SmsActivationState.WAITING_CODE.code())).isEqualTo(1);

        // 并发的另一个轮询仍以等码为前提，不能把已收码改成过期。
        var stale = mapper.find(saved.getId());
        stale.setState(SmsActivationState.EXPIRED.code());
        stale.setSmsCode(null);
        stale.setUpdatedAt(160L);
        assertThat(mapper.transition(stale, SmsActivationState.WAITING_CODE.code())).isZero();

        var completed = mapper.find(saved.getId());
        completed.setState(SmsActivationState.COMPLETED.code());
        completed.setSmsCode(null);
        completed.setReceivedAt(null);
        completed.setUpdatedAt(170L);
        assertThat(mapper.transition(completed, SmsActivationState.RECEIVED.code())).isEqualTo(1);
        var after = mapper.find(saved.getId());
        assertThat(after.getState()).isEqualTo(SmsActivationState.COMPLETED.code());
        assertThat(after.getSmsCode()).isEqualTo("004271");
        assertThat(after.getReceivedAt()).isEqualTo(150L);
    }

    private static SmsActivation row(String activationId, long createdAt) {
        var row = new SmsActivation();
        row.setActivationId(activationId);
        row.setPhoneNumber("12025550123");
        row.setServiceCode("wa");
        row.setCountryId("187");
        row.setCost(new BigDecimal("0.5"));
        row.setCurrency(840);
        row.setState(SmsActivationState.WAITING_CODE.code());
        row.setCancelAfter(createdAt);
        row.setExpiresAt(createdAt + 1_200_000);
        row.setCreatedBy(42L);
        row.setCreatedAt(createdAt);
        row.setUpdatedAt(createdAt);
        return row;
    }

    @Configuration @Import(MyBatisConfig.class)
    static class Config {
        @Bean DataSource dataSource() {
            var source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:sms_activation_mapper;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUser("sa"); source.setPassword(""); return source;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source, MybatisPlusInterceptor interceptor) throws Exception {
            var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource("mapper/account/SmsActivationMapper.xml"));
            return factory.getObject();
        }
        @Bean SmsActivationMapper mapper(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(SmsActivationMapper.class);
        }
    }
}
