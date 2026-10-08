package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.armada.account.model.dto.AccountBatchPreviewDTO;
import com.armada.account.model.dto.AccountBatchQueryDTO;
import com.armada.account.model.enums.AccountBatchOperation;
import com.armada.account.model.enums.AccountBatchScope;
import com.armada.account.model.vo.AccountBatchPreviewVO;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.impl.AccountBatchLifecycleServiceImpl;
import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 SQL 验证注销状态预估互斥计数以及账号租户、软删除边界。 */
class AccountBatchTargetMapperH2Test {

    private AccountBatchLifecycleServiceImpl service;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws Exception {
        var source = PullTaskNormalLinkH2Support.dataSource("batchPreview" + System.nanoTime());
        var db = new JdbcTemplate(source);
        db.execute("CREATE TABLE account(id BIGINT PRIMARY KEY,tenant_id BIGINT,account_group_id BIGINT,deleted_at BIGINT)");
        db.execute("CREATE TABLE account_state(account_id BIGINT,tenant_id BIGINT,account_state INT,login_state INT)");
        db.execute("CREATE TABLE account_group(id BIGINT,tenant_id BIGINT,deleted_at BIGINT)");
        db.execute("CREATE TABLE ip_proxy(id BIGINT,tenant_id BIGINT,bound_account_id BIGINT,status INT,deleted_at BIGINT)");
        db.execute("CREATE TABLE account_credential(account_id BIGINT,tenant_id BIGINT,deleted_at BIGINT)");
        db.execute("INSERT INTO account(id,tenant_id,deleted_at) VALUES (1,7,NULL),(2,7,NULL),(3,7,NULL),"
                + "(4,7,NULL),(5,8,NULL),(6,7,1),(7,7,NULL),(8,7,NULL)");
        db.execute("INSERT INTO account_state VALUES (1,7,2,2),(2,7,9,2),(3,7,9,1),(4,7,9,3),"
                + "(5,8,9,2),(6,7,9,2),(7,7,3,2),(8,7,2,2)");
        db.execute("INSERT INTO account_credential VALUES (1,7,NULL),(3,7,NULL),(4,7,NULL)");
        var config = new MyBatisConfig();
        var factory = PullTaskNormalLinkH2Support.sqlSessionFactory(source,
                config.mybatisPlusInterceptor(config.tenantLineHandler()), "mapper/account/AccountMapper.xml");
        var mapper = new SqlSessionTemplate(factory).getMapper(AccountMapper.class);
        service = new AccountBatchLifecycleServiceImpl(mapper, mock(AccountOnlineCommandService.class));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        TenantContext.set(7L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @EnumSource(AccountBatchScope.class)
    void onlinePreviewCountsDeregisteredExclusivelyBeforeLoginAndCredentialReasons(AccountBatchScope scope) {
        AccountBatchPreviewVO result = preview(AccountBatchOperation.ONLINE, scope);

        assertThat(result.matched()).isEqualTo(6);
        assertThat(result.executable()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(5);
        assertThat(result.skipReasons()).containsExactlyInAnyOrderEntriesOf(
                Map.of("DEREGISTERED", 3L, "BANNED", 1L, "MISSING_CREDENTIAL", 1L));
    }

    @ParameterizedTest
    @EnumSource(AccountBatchScope.class)
    void offlinePreviewSkipsDeregisteredButKeepsOtherExistingOfflineRules(AccountBatchScope scope) {
        AccountBatchPreviewVO result = preview(AccountBatchOperation.OFFLINE, scope);

        assertThat(result.matched()).isEqualTo(6);
        assertThat(result.executable()).isEqualTo(3);
        assertThat(result.skipped()).isEqualTo(3);
        assertThat(result.skipReasons()).containsExactlyEntriesOf(Map.of("DEREGISTERED", 3L));
    }

    private AccountBatchPreviewVO preview(AccountBatchOperation operation, AccountBatchScope scope) {
        var query = new AccountBatchQueryDTO(null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        var request = new AccountBatchPreviewDTO(operation, scope,
                scope == AccountBatchScope.IDS ? List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L) : null,
                scope == AccountBatchScope.QUERY ? query : null);
        return transaction.execute(status -> service.preview(request));
    }
}
