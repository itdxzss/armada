package com.armada.resource.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.model.vo.CountryOptionsVO;
import com.armada.platform.country.service.CountryService;
import com.armada.resource.converter.GroupDataPackageConverter;
import com.armada.resource.mapper.GroupDataPackageMapper;
import com.armada.resource.mapper.GroupDataPackagePhoneMapper;
import com.armada.resource.mapper.GroupDataPackageStatMapper;
import com.armada.resource.mapper.GroupDataPackageImportMapper;
import com.armada.resource.model.dto.GroupDataPackageCreateDTO;
import com.armada.resource.model.dto.GroupDataPackageUpdateDTO;
import com.armada.resource.model.dto.GroupDataPackageQuery;
import com.armada.resource.model.dto.GroupDataPackagePhoneQuery;
import com.armada.resource.model.enums.GroupDataPackagePhoneStatus;
import com.armada.resource.model.vo.GroupDataPackageVO;
import com.armada.resource.service.GroupDataPackageAllocationService.AllocationRef;
import com.armada.resource.service.GroupDataPackageAllocationService.ClaimRequest;
import com.armada.resource.service.GroupDataPackageAllocationService.Settlement;
import com.armada.resource.service.impl.GroupDataPackageServiceImpl;
import com.armada.resource.service.impl.GroupDataPackageAllocationServiceImpl;
import com.armada.resource.service.impl.GroupDataPackageImportWriter;
import com.armada.resource.service.impl.GroupDataPackageImportAuditService;
import com.armada.resource.service.impl.GroupDataPackageImportServiceImpl;
import com.armada.resource.service.impl.GroupDataPackageExportServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.service.GroupDataPackageTaskProjectionService;
import com.armada.task.service.PullTaskMaterialTxtParser;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** 四表及真实MyBatis、租户、事务、并发领取与大文件SQL规模验证。 */
@SpringJUnitConfig(GroupDataPackageServiceH2Test.Config.class)
@TestExecutionListeners(listeners=DependencyInjectionTestExecutionListener.class, inheritListeners=false)
class GroupDataPackageServiceH2Test {
    @Autowired private DataSource dataSource;
    @Autowired private GroupDataPackageService service;
    @Autowired private GroupDataPackageImportService imports;
    @Autowired private GroupDataPackageExportService exports;
    @Autowired private GroupDataPackageAllocationService allocations;
    @Autowired private GroupDataPackageTaskProjectionService projection;
    @Autowired private GroupDataPackageMapper packageMapper;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private SqlCounter counter;

    @BeforeEach
    void setup() throws Exception {
        reset(projection);
        when(projection.privacyRejectedPhones(anyList(), anyLong())).thenReturn(Set.of());
        TenantContext.set(7L);
        String migration = new String(new ClassPathResource("db/migration/V191__group_data_package.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8).split("-- 页面")[0];
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            for (String sql : migration.split(";")) { if (!sql.isBlank()) { statement.execute(sql); } }
        }
        counter.count.set(0);
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void importPreservesAdminAndOrderAndOverwritesByGeneration() {
        long id = create("亚洲料子");
        var first = imported(id, "append", "+63 (917) 123-4567\n639171234568\n639171234567A\ninvalid\n");
        assertThat(first.acceptedRows()).isEqualTo(2);
        assertThat(first.duplicatedRows()).isEqualTo(1);
        assertThat(first.invalidRows()).isEqualTo(1);
        var rows = service.phones(id, new GroupDataPackagePhoneQuery()).list();
        assertThat(rows).extracting(row -> row.phone()).containsExactly("639171234567", "639171234568");
        assertThat(rows.get(0).adminRequired()).isTrue();
        assertThat(rows.get(0).sourceLineNo()).isEqualTo(1);
        imported(id, "append", "639171234568A\n8613800138000\n");
        assertThat(service.phones(id, new GroupDataPackagePhoneQuery()).list().get(1).adminRequired()).isTrue();
        assertThat(service.detail(id).metrics().totalCount()).isEqualTo(3);
        var overwritten = imported(id, "overwrite", "639171234569\n");
        assertThat(overwritten.generation()).isEqualTo(2);
        assertThat(service.detail(id).metrics().totalCount()).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM group_data_package_phone", Integer.class)).isEqualTo(4);
        assertThat(imports.imports(id, new GroupDataPackageQuery()).total()).isEqualTo(3);
    }

    @Test
    void invalidOverwriteRollsBackAndLeavesFailureAudit() {
        long id = create("保留原料"); imported(id, "append", "639171234567\n");
        assertThatThrownBy(() -> imported(id, "overwrite", "bad\n"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("原包保持不变");
        assertThat(service.detail(id).generation()).isEqualTo(1);
        assertThat(service.detail(id).metrics().totalCount()).isEqualTo(1);
        assertThat(imports.imports(id, new GroupDataPackageQuery()).list().get(0).status()).isEqualTo(3);
    }

    @Test
    void claimIsIdempotentAndConflictRollsBackWholeSelection() {
        long id = create("原子领取"); imported(id, "append", "639171234567\n639171234568\n");
        var snapshot = allocations.snapshot(id, 100);
        var first = allocations.claim(new ClaimRequest(id, 1, 100L, 1, List.of(snapshot.phones().get(0).id())));
        var duplicate = allocations.claim(new ClaimRequest(id, 1, 100L, 1, List.of(snapshot.phones().get(0).id())));
        assertThat(duplicate.get(0).allocationVersion()).isEqualTo(first.get(0).allocationVersion());
        assertThatThrownBy(() -> allocations.claim(new ClaimRequest(id, 1, 101L, 1,
                snapshot.phones().stream().map(GroupDataPackageAllocationService.Phone::id).toList())))
                .isInstanceOf(BusinessException.class);
        assertThat(service.detail(id).metrics().unusedCount()).isEqualTo(1);
        assertThat(service.detail(id).metrics().claimedCount()).isEqualTo(1);
    }

    @Test
    void settlementUnknownResetAndOldVersionGuardsKeepCountsConsistent() {
        long id = create("结果回写"); imported(id, "append", "639171234567\n639171234568\n639171234569\n");
        var snapshot = allocations.snapshot(id, 100);
        var claimed = allocations.claim(new ClaimRequest(id, 1, 100L, 1,
                snapshot.phones().stream().map(GroupDataPackageAllocationService.Phone::id).toList()));
        var first = ref(claimed.get(0)); var second = ref(claimed.get(1)); var third = ref(claimed.get(2));
        List<Settlement> results = List.of(new Settlement(first, GroupDataPackagePhoneStatus.UNKNOWN),
                new Settlement(second, GroupDataPackagePhoneStatus.RETRYABLE_FAILED),
                new Settlement(third, GroupDataPackagePhoneStatus.PRIVACY_REJECTED));
        allocations.settle(results); allocations.settle(results);
        allocations.release(List.of(first));
        assertThat(service.resetFailed(id)).isEqualTo(1);
        var metrics = service.detail(id).metrics();
        assertThat(metrics.unknownCount()).isEqualTo(1);
        assertThat(metrics.failedCount()).isEqualTo(1);
        assertThat(metrics.privacyRejectedCount()).isEqualTo(1);
        var reclaimed = allocations.claim(new ClaimRequest(id, 1, 101L, 2, List.of(second.phoneId()))).get(0);
        allocations.settle(List.of(new Settlement(second, GroupDataPackagePhoneStatus.SUCCESS),
                new Settlement(new AllocationRef(reclaimed.id(), reclaimed.allocationVersion(), 101L, 2),
                        GroupDataPackagePhoneStatus.PRIVACY_REJECTED)));
        assertThat(service.detail(id).metrics().successCount()).isZero();
        assertThat(service.detail(id).metrics().privacyRejectedCount()).isEqualTo(2);
        assertThat(reclaimed.allocationVersion()).isEqualTo(2);
        assertThatThrownBy(() -> service.delete(id)).hasMessageContaining("待确认");
    }

    @Test
    void replacementExecutionKeepsAllocationAndCanProjectNewGroupState() {
        long id = create("换群"); imported(id, "append", "639171234567\n");
        var phone = allocations.claim(new ClaimRequest(id, 1, 100L, 1,
                List.of(allocations.snapshot(id, 1).phones().get(0).id()))).get(0);
        allocations.settle(List.of(new Settlement(ref(phone), GroupDataPackagePhoneStatus.SUCCESS)));
        allocations.settle(List.of(new Settlement(ref(phone), GroupDataPackagePhoneStatus.CLAIMED)));
        var result = service.detail(id).metrics();
        assertThat(result.totalCount()).isEqualTo(1);
        assertThat(result.unusedCount()).isZero(); assertThat(result.claimedCount()).isEqualTo(1);
        assertThat(result.successCount()).isZero();
        assertThat(jdbc().queryForObject("SELECT allocation_version FROM group_data_package_phone", Long.class)).isEqualTo(1);
    }

    @Test
    void queriesAndExportsDoNotCrossTenantAndMetadataUsesVersion() {
        long first = create("客群甲"); imported(first, "append", "639171234567A\n");
        create("别的包");
        GroupDataPackageQuery query = new GroupDataPackageQuery(); query.setName("客群"); query.setPageSize(1);
        assertThat(service.list(query).total()).isEqualTo(1);
        assertThat(service.list(query).list().get(0).primaryCountryIso2()).isEqualTo("PH");
        service.update(first, new GroupDataPackageUpdateDTO("客群乙", "说明", 1));
        assertThatThrownBy(() -> service.update(first, new GroupDataPackageUpdateDTO("覆盖", "", 1)))
                .isInstanceOf(BusinessException.class);
        var txt = exports.export(List.of(first), "all", "txt");
        assertThat(new String(txt.bytes(), StandardCharsets.UTF_8)).isEqualTo("639171234567A\n");
        assertThat(txt.exportedCount()).isEqualTo(1);
        TenantContext.set(8L);
        assertThat(service.list(new GroupDataPackageQuery()).total()).isZero();
        assertThatThrownBy(() -> service.detail(first)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> exports.export(List.of(first), "all", "txt")).isInstanceOf(BusinessException.class);
    }

    @Test
    void privacyFilterUsesReliableHistoryAndDoesNotTreatCountryAsForbidden() {
        when(projection.privacyRejectedPhones(anyList(), anyLong())).thenReturn(Set.of("639171234567"));
        long id = create("隐私过滤");
        var result = imports.importPhones(new GroupDataPackageImportService.ImportRequest(id, "append",
                txt("639171234567\n8613800138000\n"), 60, 9L));
        assertThat(result.privacyFilteredRows()).isEqualTo(1);
        assertThat(result.acceptedRows()).isEqualTo(1);
        assertThat(service.phones(id, new GroupDataPackagePhoneQuery()).list().get(0).phone()).isEqualTo("8613800138000");
    }

    @Test
    void activeTaskBlocksDestructiveOperationsAndAppendDoesNotChangeFrozenAdmin() {
        long id = create("活动保护"); imported(id, "append", "639171234567\n");
        var phone = allocations.claim(new ClaimRequest(id, 1, 100L, 1,
                List.of(allocations.snapshot(id, 1).phones().get(0).id()))).get(0);
        imported(id, "append", "639171234567A\n");
        assertThat(service.phones(id, new GroupDataPackagePhoneQuery()).list().get(0).adminRequired()).isFalse();
        allocations.settle(List.of(new Settlement(ref(phone), GroupDataPackagePhoneStatus.SUCCESS)));
        org.mockito.Mockito.doThrow(new BusinessException(com.armada.shared.exception.ErrorCode.CONFLICT, "活动引用"))
                .when(projection).assertNotActivelyUsed(id);
        assertThatThrownBy(() -> service.resetFailed(id)).hasMessageContaining("活动引用");
        assertThatThrownBy(() -> service.delete(id)).hasMessageContaining("活动引用");
        assertThatThrownBy(() -> imported(id, "overwrite", "639171234568\n")).hasMessageContaining("活动引用");
        assertThat(service.detail(id).generation()).isEqualTo(1);
    }

    @Test
    void readPagesAndExportsOnlyReadCurrentProjectionWithoutRescanningTasks() {
        long id = create("只读投影"); imported(id, "append", "639171234567\n");
        reset(projection);
        service.list(new GroupDataPackageQuery()); service.detail(id);
        service.phones(id, new GroupDataPackagePhoneQuery()); exports.export(List.of(id), "all", "txt");
        org.mockito.Mockito.verifyNoInteractions(projection);
    }

    @Test
    void hundredThousandImportAndClaimUseBatchedSql() {
        long id = create("十万号码");
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 100_000; index++) { text.append(63_900_000_000L + index).append('\n'); }
        counter.count.set(0);
        assertThat(imported(id, "append", text.toString()).acceptedRows()).isEqualTo(100_000);
        long importSqlCount = counter.count.get();
        assertThat(importSqlCount).isLessThan(1000);
        assertThatThrownBy(() -> allocations.snapshot(id, 99_999)).hasMessageContaining("请拆分数据包");
        var snapshot = allocations.snapshot(id, 100_000);
        counter.count.set(0);
        var claimed = allocations.claim(new ClaimRequest(id, 1, 100L, 1,
                snapshot.phones().stream().map(GroupDataPackageAllocationService.Phone::id).toList()));
        assertThat(claimed).hasSize(100_000);
        assertThat(counter.count.get()).isLessThan(1000);
        assertThat(service.detail(id).metrics().claimedCount()).isEqualTo(100_000);
    }

    @Test
    void packageLockSerializesConcurrentClaims() throws Exception {
        long id = create("并发领取"); imported(id, "append", "639171234567\n");
        long phone = allocations.snapshot(id, 1).phones().get(0).id();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        try {
            var holder = executor.submit(() -> {
                TenantContext.set(7L);
                try { new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    packageMapper.lockActive(id); locked.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("timeout"); } }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                    allocations.claim(new ClaimRequest(id, 1, 100L, 1, List.of(phone)));
                }); } finally { TenantContext.clear(); }
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var competing = executor.submit(() -> {
                TenantContext.set(7L);
                try { return allocations.claim(new ClaimRequest(id, 1, 101L, 1, List.of(phone))); }
                finally { TenantContext.clear(); }
            });
            Thread.sleep(150);
            assertThat(competing.isDone()).isFalse();
            release.countDown(); holder.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> competing.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
            assertThat(service.detail(id).metrics().claimedCount()).isEqualTo(1);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void deletionRechecksClaimCommittedAfterItsInitialRead() throws Exception {
        long id = create("删除并发领取");
        imported(id, "append", "639171234567");
        long phone = allocations.snapshot(id, 1).phones().get(0).id();
        CountDownLatch initialRead = new CountDownLatch(1);
        CountDownLatch claimed = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            initialRead.countDown();
            if (!claimed.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("timeout"); }
            return null;
        }).when(projection).synchronize(List.of(id));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var deletion = executor.submit(() -> {
                TenantContext.set(7L);
                try { service.delete(id); } finally { TenantContext.clear(); }
            });
            assertThat(initialRead.await(5, TimeUnit.SECONDS)).isTrue();
            allocations.claim(new ClaimRequest(id, 1, 100L, 1, List.of(phone)));
            claimed.countDown();
            assertThatThrownBy(() -> deletion.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BusinessException.class);
            assertThat(service.detail(id).metrics().claimedCount()).isEqualTo(1);
        } finally { claimed.countDown(); executor.shutdownNow(); }
    }

    private long create(String name) { return service.create(new GroupDataPackageCreateDTO(name, ""), 9L).id(); }
    private com.armada.resource.model.vo.GroupDataPackageImportResultVO imported(long id, String mode, String text) {
        return imports.importPhones(new GroupDataPackageImportService.ImportRequest(id, mode, txt(text), 0, 9L));
    }
    private static MockMultipartFile txt(String text) {
        return new MockMultipartFile("file", "料子.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));
    }
    private static AllocationRef ref(GroupDataPackageAllocationService.Phone phone) {
        return new AllocationRef(phone.id(), phone.allocationVersion(), 100L, 1);
    }
    private JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }

    @Intercepts({@Signature(type=Executor.class, method="update", args={MappedStatement.class,Object.class}),
            @Signature(type=Executor.class, method="query", args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class})})
    static class SqlCounter implements Interceptor {
        final AtomicLong count = new AtomicLong();
        @Override public Object intercept(Invocation invocation) throws Throwable { count.incrementAndGet(); return invocation.proceed(); }
    }

    @Configuration
    @EnableTransactionManagement
    @Import({GroupDataPackageServiceImpl.class,GroupDataPackageAllocationServiceImpl.class,
            GroupDataPackageImportWriter.class,GroupDataPackageImportAuditService.class,GroupDataPackageImportServiceImpl.class,
            GroupDataPackageExportServiceImpl.class,GroupDataPackageFileParser.class,PullTaskMaterialTxtParser.class})
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:group_package;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");
            return source;
        }
        @Bean PlatformTransactionManager transactions(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean SqlCounter counter() { return new SqlCounter(); }
        @Bean SqlSessionFactory factory(DataSource source, SqlCounter counter) throws Exception {
            var bean = new MybatisSqlSessionFactoryBean(); bean.setDataSource(source);
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            bean.setConfiguration(config);
            MyBatisConfig production = new MyBatisConfig();
            bean.setPlugins(production.mybatisPlusInterceptor(production.tenantLineHandler()), counter);
            bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/resource/GroupDataPackage*Mapper.xml"));
            return bean.getObject();
        }
        @Bean SqlSessionTemplate template(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean GroupDataPackageMapper packages(SqlSessionTemplate template) { return template.getMapper(GroupDataPackageMapper.class); }
        @Bean GroupDataPackagePhoneMapper phones(SqlSessionTemplate template) { return template.getMapper(GroupDataPackagePhoneMapper.class); }
        @Bean GroupDataPackageStatMapper stats(SqlSessionTemplate template) { return template.getMapper(GroupDataPackageStatMapper.class); }
        @Bean GroupDataPackageImportMapper imports(SqlSessionTemplate template) { return template.getMapper(GroupDataPackageImportMapper.class); }
        @Bean GroupDataPackageConverter converter() { return Mappers.getMapper(GroupDataPackageConverter.class); }
        @Bean GroupDataPackageTaskProjectionService projection() { return mock(GroupDataPackageTaskProjectionService.class); }
        @Bean CountryService countries() {
            CountryService service = mock(CountryService.class);
            var ph = new CountryOptionVO("PH","PH","菲律宾","Philippines","+63","",false,"ASIA");
            var cn = new CountryOptionVO("CN","CN","中国","China","+86","",false,"ASIA");
            when(service.options("marketing-export")).thenReturn(new CountryOptionsVO(List.of(ph,cn)));
            when(service.activePhonePrefixResolver()).thenReturn(phone -> phone.startsWith("63") ? ph : phone.startsWith("86") ? cn : null);
            return service;
        }
    }
}
