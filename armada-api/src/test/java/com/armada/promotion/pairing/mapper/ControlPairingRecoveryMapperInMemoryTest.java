package com.armada.promotion.pairing.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 Mapper、租户插件和事务上的会话恢复及唯一约束回归。 */
class ControlPairingRecoveryMapperInMemoryTest {
    private PromotionPairingSessionMapper mapper;
    private TransactionTemplate transaction;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:pairing_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE promotion_pairing_session (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  pairing_scene TINYINT NOT NULL, promotion_channel_id BIGINT, channel_name VARCHAR(128),
                  owner_user_id BIGINT, account_group_id BIGINT, remark VARCHAR(255),
                  session_token_hash VARCHAR(64) NOT NULL UNIQUE, phone VARCHAR(32) NOT NULL,
                  protocol_account_id VARCHAR(64) NOT NULL, pairing_id VARCHAR(128), pairing_code VARCHAR(16),
                  status TINYINT NOT NULL, proxy_id BIGINT, proxy_session_id VARCHAR(64),
                  proxy_region VARCHAR(64), proxy_source VARCHAR(64), account_id BIGINT,
                  expires_at BIGINT, error_code VARCHAR(64), error_message VARCHAR(255),
                  completed_at BIGINT, created_at BIGINT, updated_at BIGINT,
                  active_phone VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN status IN (1,2,3) THEN phone ELSE NULL END),
                  active_protocol_account_id VARCHAR(64) GENERATED ALWAYS AS
                    (CASE WHEN status IN (1,2,3) THEN protocol_account_id ELSE NULL END),
                  UNIQUE(active_phone), UNIQUE(active_protocol_account_id)
                )
                """);
        MyBatisConfig config = new MyBatisConfig();
        MybatisConfiguration mybatis = new MybatisConfiguration();
        mybatis.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(mybatis);
        factory.setPlugins(config.mybatisPlusInterceptor(config.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/promotion/pairing/PromotionPairingSessionMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(PromotionPairingSessionMapper.class);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        TenantContext.set(7L);
    }

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void recoveryRequiresOriginalTenantOwnerAndControlScene() {
        PromotionPairingSession row = row("15555550101", 81L, 2);
        transaction.executeWithoutResult(status -> mapper.insert(row));
        assertThat(mapper.selectLatestControlByPhone(row.getPhone(), 7L, 81L).getId()).isEqualTo(row.getId());
        assertThat(mapper.selectLatestControlByPhone(row.getPhone(), 8L, 81L)).isNull();
        assertThat(mapper.selectLatestControlByPhone(row.getPhone(), 7L, 82L)).isNull();
        PromotionPairingSession promotion = row("15555550102", 81L, 1);
        mapper.insert(promotion);
        assertThat(mapper.selectLatestControlByPhone(promotion.getPhone(), 7L, 81L)).isNull();
    }

    @Test
    void duplicateInsertRollsBackAndOriginalSessionRemainsRecoverable() {
        PromotionPairingSession original = row("15555550101", 81L, 2);
        transaction.executeWithoutResult(status -> mapper.insert(original));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                mapper.insert(row(original.getPhone(), 81L, 2))))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(mapper.selectLatestControlByPhone(original.getPhone(), 7L, 81L).getId()).isEqualTo(original.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM promotion_pairing_session", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWaitForTheWinningTransactionThenRecoverItsSession() throws Exception {
        var inserted = new java.util.concurrent.CountDownLatch(1);
        var commit = new java.util.concurrent.CountDownLatch(1);
        var competing = new java.util.concurrent.CountDownLatch(1);
        var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        PromotionPairingSession original = row("15555550101", 81L, 2);
        try {
            var winner = threads.submit(() -> {
                TenantContext.set(7L);
                try {
                    transaction.executeWithoutResult(status -> {
                        mapper.insert(original);
                        inserted.countDown();
                        try {
                            if (!commit.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new IllegalStateException("commit barrier timed out");
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                    });
                } finally { TenantContext.clear(); }
            });
            assertThat(inserted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var retry = threads.submit(() -> {
                TenantContext.set(7L);
                try {
                    competing.countDown();
                    assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                            mapper.insert(row(original.getPhone(), 81L, 2))))
                            .isInstanceOf(DuplicateKeyException.class);
                    return mapper.selectLatestControlByPhone(original.getPhone(), 7L, 81L).getId();
                } finally { TenantContext.clear(); }
            });
            assertThat(competing.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> retry.get(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            commit.countDown();
            winner.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(retry.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(original.getId());
        } finally {
            commit.countDown();
            threads.shutdownNow();
        }
    }

    @Test
    void terminalSessionReleasesPhoneAndRecoveryReturnsNewestAttempt() {
        PromotionPairingSession original = row("15555550101", 81L, 2);
        mapper.insert(original);
        mapper.markTerminal(original.getId(), 7L, 6, "PAIRING_EXPIRED", "expired", 10L);
        PromotionPairingSession next = row(original.getPhone(), 81L, 2);
        mapper.insert(next);
        assertThat(mapper.selectLatestControlByPhone(original.getPhone(), 7L, 81L).getId()).isEqualTo(next.getId());
        TenantContext.set(8L);
        assertThatThrownBy(() -> mapper.insert(row(original.getPhone(), 82L, 2)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(mapper.selectLatestControlByPhone(original.getPhone(), 8L, 82L)).isNull();
    }

    private PromotionPairingSession row(String phone, long owner, int scene) {
        PromotionPairingSession row = new PromotionPairingSession();
        row.setPairingScene(scene);
        row.setOwnerUserId(owner);
        row.setAccountGroupId(301L);
        row.setSessionTokenHash(UUID.randomUUID().toString());
        row.setPhone(phone);
        row.setProtocolAccountId("acc_pair_" + UUID.randomUUID());
        row.setStatus(1);
        row.setExpiresAt(180_001L);
        row.setCreatedAt(1L);
        row.setUpdatedAt(1L);
        return row;
    }
}
