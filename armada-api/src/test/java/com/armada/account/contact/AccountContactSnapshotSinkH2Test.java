package com.armada.account.contact;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.mapper.AccountContactSyncMapper;
import com.armada.account.contact.model.entity.AccountContactSync;
import com.armada.account.contact.service.AccountContactNormalizer;
import com.armada.account.contact.service.impl.AccountContactSnapshotSink;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.kafka.consumer.contact.AccountContactsReportedEvent;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/**
 * 通讯录快照落库的 H2 MySQL 模式测试。
 *
 * <p>纯 Mockito 测试证明不了的东西在这里证明：{@code ON DUPLICATE KEY UPDATE} 的真实幂等性、
 * {@code deleteStale} 让删除真的收敛、精确 {@code synced_at} 计数、
 * 以及租户拦截器是否把 {@code tenant_id} 注入到这三张表。</p>
 *
 * <p><b>H2 证明不了的仍然只能上真库</b>：Flyway 迁移本身、utf8mb4 排序规则、
 * 真并发下的抢占。那些留给 {@code dbtest.sh}。</p>
 */
@SpringJUnitConfig(AccountContactSnapshotSinkH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class AccountContactSnapshotSinkH2Test {

    private static final long TENANT_ID = 7L;
    private static final long ACCOUNT_ID = 11L;
    private static final long CUTOFF = 1_700_000_005_000L;
    private static final long OLD_CUTOFF = 1_699_999_000_000L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private AccountContactMapper contactMapper;
    @Autowired
    private AccountContactSyncMapper syncMapper;
    @Autowired
    private AccountStateMapper accountStateMapper;

    private AccountContactSnapshotSink sink;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE account_contact (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL,
                  contact_phone VARCHAR(32) NOT NULL,
                  contact_jid VARCHAR(64) NOT NULL,
                  full_name VARCHAR(128),
                  first_name VARCHAR(128),
                  push_name VARCHAR(128),
                  business_name VARCHAR(128),
                  is_named TINYINT NOT NULL DEFAULT 0,
                  is_mutual TINYINT NOT NULL DEFAULT 0,
                  synced_at BIGINT NOT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  CONSTRAINT uq_account_contact UNIQUE (tenant_id, account_id, contact_phone)
                )
                """);
        execute("""
                CREATE TABLE account_contact_sync (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL,
                  last_synced_at BIGINT,
                  last_sync_source VARCHAR(32),
                  contact_num INT NOT NULL DEFAULT 0,
                  named_num INT NOT NULL DEFAULT 0,
                  mutual_num INT NOT NULL DEFAULT 0,
                  sync_status VARCHAR(16) NOT NULL DEFAULT 'NEVER',
                  fail_reason VARCHAR(255),
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  CONSTRAINT uq_account_contact_sync UNIQUE (tenant_id, account_id)
                )
                """);
        execute("""
                CREATE TABLE account_state (
                  account_id BIGINT NOT NULL,
                  tenant_id BIGINT NOT NULL,
                  contact_named_num INT NOT NULL DEFAULT 0,
                  contact_mutual_num INT NOT NULL DEFAULT 0,
                  updated_at BIGINT,
                  PRIMARY KEY (tenant_id, account_id)
                )
                """);
        execute("""
                INSERT INTO account_state
                  (account_id, tenant_id, contact_named_num, contact_mutual_num, updated_at)
                VALUES (11, 7, 0, 0, 0)
                """);
        sink = new AccountContactSnapshotSink(
                contactMapper, syncMapper, accountStateMapper,
                new AccountContactNormalizer(), () -> 2_000L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---------- 用例 ----------

    @Test
    void completeSnapshotRemovesContactsTheOwnerDeleted() throws SQLException {
        // 整件事的目的：号主删掉的联系人必须真的消失，增量事件做不到这一点
        givenExistingContact("8613800000001", OLD_CUTOFF);
        givenExistingContact("8613800000002", OLD_CUTOFF);

        sink.handle(chunk(0, 1, 1, true, List.of("8613800000001")));

        assertThat(phones()).containsExactly("8613800000001");
    }

    @Test
    void emptySnapshotClearsEverything() throws SQLException {
        // 「这个号一个联系人都没有」也必须能收敛
        givenExistingContact("8613800000001", OLD_CUTOFF);

        sink.handle(chunk(0, 1, 0, true, List.of()));

        assertThat(phones()).isEmpty();
    }

    @Test
    void upsertIsIdempotentOnRepeatedDelivery() throws SQLException {
        // Kafka 至少一次投递：同一片重投不得产生重复行
        AccountContactsReportedEvent event =
                chunk(0, 1, 1, true, List.of("8613800000001"));

        sink.handle(event);
        sink.handle(event);

        assertThat(phones()).containsExactly("8613800000001");
    }

    @Test
    void reDeliveryDoesNotWipeTheSnapshot() throws SQLException {
        // 重投时 deleteStale 用的是同一个 cutoff，不能把本批自己删掉
        sink.handle(chunk(0, 2, 2, true, List.of("8613800000001", "8613800000002")));

        sink.handle(chunk(0, 2, 2, true, List.of("8613800000001", "8613800000002")));

        assertThat(phones()).containsExactly("8613800000001", "8613800000002");
    }

    @Test
    void missingChunkKeepsStaleRowsInsteadOfDeletingHalfTheAddressBook() throws SQLException {
        // 丢片时宁可留脏数据，也不能把号主的通讯录删掉一半
        givenExistingContact("8613800000009", OLD_CUTOFF);

        // 本快照共 5 条，这一片只带来 1 条 —— 还差 4 条没到
        sink.handle(chunk(0, 5, 5, true, List.of("8613800000001")));

        assertThat(phones()).contains("8613800000009");
        assertThat(status()).isEqualTo(AccountContactSync.STATUS_SYNCING);
    }

    @Test
    void outOfOrderChunksStillConverge() throws SQLException {
        // 收齐判据靠计数而不是「收到最后一片」：末片先到也必须能收敛
        givenExistingContact("8613800000009", OLD_CUTOFF);

        sink.handle(chunk(1, 2, 2, true, List.of("8613800000002")));
        assertThat(phones()).contains("8613800000009");

        sink.handle(chunk(0, 2, 2, true, List.of("8613800000001")));

        assertThat(phones()).containsExactly("8613800000001", "8613800000002");
        assertThat(status()).isEqualTo(AccountContactSync.STATUS_SUCCESS);
    }

    @Test
    void partialSnapshotKeepsLeftovers() throws SQLException {
        givenExistingContact("8613800000009", OLD_CUTOFF);

        sink.handle(chunk(0, 1, 1, false, List.of("8613800000001")));

        assertThat(phones()).contains("8613800000009");
        assertThat(status()).isEqualTo(AccountContactSync.STATUS_PARTIAL);
    }

    @Test
    void countsWrittenBackAreTheWholeSnapshotNotTheLastChunk() throws SQLException {
        // 用本片的归一化计数会把 1200 人的快照写成个位数
        sink.handle(chunk(0, 3, 3, true,
                List.of("8613800000001", "8613800000002")));
        sink.handle(chunk(1, 3, 3, true, List.of("8613800000003")));

        assertThat(namedNumOnAccountState()).isEqualTo(3);
        AccountContactSync state = syncMapper.selectByAccountId(ACCOUNT_ID);
        assertThat(state.getContactNum()).isEqualTo(3);
        assertThat(state.getNamedNum()).isEqualTo(3);
    }

    @Test
    void syncedAtIsTheProtocolCutoffNotTheLocalClock() throws SQLException {
        sink.handle(chunk(0, 1, 1, true, List.of("8613800000001")));

        assertThat(singleLong("SELECT synced_at FROM account_contact")).isEqualTo(CUTOFF);
        assertThat(syncMapper.selectByAccountId(ACCOUNT_ID).getLastSyncedAt()).isEqualTo(CUTOFF);
    }

    @Test
    void tenantInterceptorStampsTenantIdOnTheNewTables() throws SQLException {
        // 事件从 Kafka 线程进来，没有 HTTP 请求带租户；租户必须由事件自己声明并被拦截器写进去
        sink.handle(chunk(0, 1, 1, true, List.of("8613800000001")));

        assertThat(singleLong("SELECT tenant_id FROM account_contact")).isEqualTo(TENANT_ID);
        assertThat(singleLong("SELECT tenant_id FROM account_contact_sync")).isEqualTo(TENANT_ID);
    }

    @Test
    void anotherTenantsRowsAreNeverTouched() throws SQLException {
        // 租户隔离：别的租户同号同账号的行不能被本次快照删掉
        execute("""
                INSERT INTO account_contact
                  (tenant_id, account_id, contact_phone, contact_jid,
                   is_named, is_mutual, synced_at, created_at, updated_at)
                VALUES (99, 11, '8613899999999', '8613899999999@s.whatsapp.net',
                        1, 0, %d, 0, 0)
                """.formatted(OLD_CUTOFF));

        sink.handle(chunk(0, 1, 1, true, List.of("8613800000001")));

        assertThat(singleLong(
                "SELECT COUNT(*) FROM account_contact WHERE tenant_id = 99")).isEqualTo(1L);
    }

    @Test
    void namedCountOnlyCountsContactsWithAName() throws SQLException {
        sink.handle(chunkWithUnnamed(List.of("8613800000001"), List.of("8613800000002")));

        assertThat(contactMapper.countBySyncedAt(ACCOUNT_ID, CUTOFF)).isEqualTo(2);
        assertThat(contactMapper.countNamedBySyncedAt(ACCOUNT_ID, CUTOFF)).isEqualTo(1);
        assertThat(namedNumOnAccountState()).isEqualTo(1);
    }

    @Test
    void countBySyncedAtIsExactNotGreaterOrEqual() throws SQLException {
        // 写成 >= 会把上一轮的行也算进来，收齐判据会提前成立并误删
        givenExistingContact("8613800000009", OLD_CUTOFF);

        sink.handle(chunk(0, 2, 2, true, List.of("8613800000001")));

        assertThat(contactMapper.countBySyncedAt(ACCOUNT_ID, OLD_CUTOFF)).isEqualTo(1);
        assertThat(contactMapper.countBySyncedAt(ACCOUNT_ID, CUTOFF)).isEqualTo(1);
    }

    @Test
    void taskExpansionOnlyEverSeesTheLatestSnapshot() throws SQLException {
        // 快照落库后，任务展开读的是同一份数据；上一轮已删的号不能再被发出去
        givenExistingContact("8613800000009", OLD_CUTOFF);

        sink.handle(chunk(0, 1, 2, true,
                List.of("8613800000001", "8613800000002")));

        assertThat(contactMapper.selectNamedByAccount(ACCOUNT_ID, 100))
                .extracting(com.armada.account.contact.model.entity.AccountContact::getContactPhone)
                .containsExactly("8613800000001", "8613800000002");
    }

    @Test
    void perAccountSendCapIsAppliedBySql() throws SQLException {
        // 每号发送上限靠 LIMIT 下推，不是查全量再截断
        sink.handle(chunk(0, 1, 3, true,
                List.of("8613800000001", "8613800000002", "8613800000003")));

        assertThat(contactMapper.selectNamedByAccount(ACCOUNT_ID, 2)).hasSize(2);
    }

    @Test
    void contactsWithoutANameAreNotSendTargets() throws SQLException {
        // 发送目标集口径是「通讯录里有名字」，只有对方昵称的号不算
        sink.handle(chunkWithUnnamed(List.of("8613800000001"), List.of("8613800000002")));

        assertThat(contactMapper.selectNamedByAccount(ACCOUNT_ID, 100))
                .extracting(com.armada.account.contact.model.entity.AccountContact::getContactPhone)
                .containsExactly("8613800000001");
    }

    // ---------- 夹具 ----------

    private AccountContactsReportedEvent chunk(
            int chunkSeq, int chunkCount, int totalCount, boolean complete, List<String> phones) {
        List<AccountContactsReportedEvent.ReportedContact> contacts = new ArrayList<>();
        for (String phone : phones) {
            contacts.add(new AccountContactsReportedEvent.ReportedContact(
                    phone, phone + "@s.whatsapp.net", "名字" + phone, null, null, null));
        }
        return new AccountContactsReportedEvent(
                "evt_1", TENANT_ID, ACCOUNT_ID, "acc_1", "snap-1",
                1_700_000_000_000L, CUTOFF, complete,
                chunkSeq, chunkCount, totalCount, contacts);
    }

    private AccountContactsReportedEvent chunkWithUnnamed(
            List<String> named, List<String> unnamed) {
        List<AccountContactsReportedEvent.ReportedContact> contacts = new ArrayList<>();
        for (String phone : named) {
            contacts.add(new AccountContactsReportedEvent.ReportedContact(
                    phone, phone + "@s.whatsapp.net", "有名字", null, null, null));
        }
        for (String phone : unnamed) {
            contacts.add(new AccountContactsReportedEvent.ReportedContact(
                    phone, phone + "@s.whatsapp.net", null, null, "只有对方昵称", null));
        }
        return new AccountContactsReportedEvent(
                "evt_1", TENANT_ID, ACCOUNT_ID, "acc_1", "snap-1",
                1_700_000_000_000L, CUTOFF, true,
                0, 1, named.size() + unnamed.size(), contacts);
    }

    private void givenExistingContact(String phone, long syncedAt) throws SQLException {
        execute("""
                INSERT INTO account_contact
                  (tenant_id, account_id, contact_phone, contact_jid,
                   is_named, is_mutual, synced_at, created_at, updated_at)
                VALUES (%d, %d, '%s', '%s@s.whatsapp.net', 1, 0, %d, 0, 0)
                """.formatted(TENANT_ID, ACCOUNT_ID, phone, phone, syncedAt));
    }

    private List<String> phones() throws SQLException {
        List<String> phones = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT contact_phone FROM account_contact WHERE tenant_id = " + TENANT_ID
                             + " ORDER BY contact_phone")) {
            while (rs.next()) {
                phones.add(rs.getString(1));
            }
        }
        return phones;
    }

    private String status() {
        return syncMapper.selectByAccountId(ACCOUNT_ID).getSyncStatus();
    }

    private int namedNumOnAccountState() throws SQLException {
        return (int) singleLong("SELECT contact_named_num FROM account_state");
    }

    private long singleLong(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:account_contact_snapshot_test;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            h2.setUser("sa");
            h2.setPassword("");
            return h2;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(
                    new ClassPathResource("mapper/account/AccountContactMapper.xml"),
                    new ClassPathResource("mapper/account/AccountContactSyncMapper.xml"),
                    new ClassPathResource("mapper/account/AccountStateMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        AccountContactMapper contactMapper(SqlSessionTemplate template) {
            return template.getMapper(AccountContactMapper.class);
        }

        @Bean
        AccountContactSyncMapper syncMapper(SqlSessionTemplate template) {
            return template.getMapper(AccountContactSyncMapper.class);
        }

        @Bean
        AccountStateMapper accountStateMapper(SqlSessionTemplate template) {
            return template.getMapper(AccountStateMapper.class);
        }
    }
}
