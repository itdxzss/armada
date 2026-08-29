package com.armada.hyperlink.data.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.data.converter.DataPackageConverter;
import com.armada.hyperlink.data.mapper.DataPackageImportMapper;
import com.armada.hyperlink.data.mapper.DataPackageMapper;
import com.armada.hyperlink.data.mapper.DataPackagePhoneMapper;
import com.armada.hyperlink.data.mapper.DataPackageStatMapper;
import com.armada.hyperlink.data.model.dto.DataPackageCreateDTO;
import com.armada.hyperlink.data.model.dto.DataPackagePhoneQuery;
import com.armada.hyperlink.data.model.dto.DataPackageQuery;
import com.armada.hyperlink.data.model.dto.DataPackageUpdateDTO;
import com.armada.hyperlink.data.model.enums.DataPackageImportMode;
import com.armada.hyperlink.data.model.enums.DataPackageClickExportFormat;
import com.armada.hyperlink.data.model.enums.DataPackageUsageStatus;
import com.armada.hyperlink.data.model.vo.DataPackageDetailVO;
import com.armada.hyperlink.data.model.vo.DataPackageExportFile;
import com.armada.hyperlink.data.model.vo.DataPackageImportResultVO;
import com.armada.hyperlink.data.service.impl.DataPackageImportServiceImpl;
import com.armada.hyperlink.data.service.impl.DataPackageMaintenanceServiceImpl;
import com.armada.hyperlink.data.service.impl.DataPackageServiceImpl;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.model.vo.CountryOptionsVO;
import com.armada.platform.country.model.vo.CountryReferenceVO;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** 数据包真实 H2/MyBatis XML、租户、事务、代际、清理和并发锁测试。 */
@SpringJUnitConfig(DataPackageServiceH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class DataPackageServiceH2Test {

    private static final long TENANT_ID = 7L;

    @org.springframework.beans.factory.annotation.Autowired private DataSource dataSource;
    @org.springframework.beans.factory.annotation.Autowired private DataPackageService service;
    @org.springframework.beans.factory.annotation.Autowired private DataPackageImportService importService;
    @org.springframework.beans.factory.annotation.Autowired private DataPackageMaintenanceService maintenanceService;
    @org.springframework.beans.factory.annotation.Autowired private DataPackageMapper packageMapper;
    @org.springframework.beans.factory.annotation.Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        execute("DROP ALL OBJECTS");
        createSchema();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void appendAndOverwriteMaintainCountsCountriesAndCurrentGeneration() {
        DataPackageDetailVO created = service.create(
                new DataPackageCreateDTO(" 菲律宾新客 ", " 8 月活动 "), 9L);

        DataPackageImportResultVO first = importService.importPhones(
                created.id(), DataPackageImportMode.APPEND,
                txt("first.txt", "\uFEFF639171234567\n639171234567\nabc\n639181234567\n"), 9L);
        assertThat(first.totalRows()).isEqualTo(4);
        assertThat(first.acceptedRows()).isEqualTo(2);
        assertThat(first.invalidRows()).isEqualTo(1);
        assertThat(first.duplicatedRows()).isEqualTo(1);
        assertThat(first.phoneCountAfterImport()).isEqualTo(2);

        DataPackageImportResultVO second = importService.importPhones(
                created.id(), DataPackageImportMode.APPEND,
                txt("second.txt", "639171234567\n123456789\n"), 9L);
        assertThat(second.acceptedRows()).isEqualTo(1);
        assertThat(second.duplicatedRows()).isEqualTo(1);
        assertThat(second.phoneCountAfterImport()).isEqualTo(3);

        DataPackageImportResultVO overwritten = importService.importPhones(
                created.id(), DataPackageImportMode.OVERWRITE,
                txt("overwrite.txt", "639171234567\n555555555\n"), 9L);
        assertThat(overwritten.generation()).isEqualTo(2);
        assertThat(overwritten.acceptedRows()).isEqualTo(2);

        DataPackageDetailVO detail = service.detail(created.id());
        assertThat(detail.currentGeneration()).isEqualTo(2);
        assertThat(detail.version()).isEqualTo(1);
        assertThat(detail.countries()).containsExactly("PH", null);
        assertThat(detail.primaryCountryIso2()).isEqualTo("PH");
        assertThat(detail.metrics().totalCount()).isEqualTo(2);
        assertThat(detail.metrics().unusedCount()).isEqualTo(2);
        assertThat(detail.metrics().usedCount()).isZero();
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM data_package_phone WHERE data_package_id = ?",
                Integer.class, created.id())).isEqualTo(5);

        DataPackagePhoneQuery phoneQuery = new DataPackagePhoneQuery();
        phoneQuery.setCountryIso2("UNKNOWN");
        assertThat(service.phones(created.id(), phoneQuery).list())
                .extracting(item -> item.phone())
                .containsExactly("555555555");
    }

    @Test
    void listUsesCommaCountriesUnknownAndForTaskWithoutCrossTenantLeakage() {
        DataPackageDetailVO tenantSeven = service.create(
                new DataPackageCreateDTO("同名包", null), 9L);
        importService.importPhones(tenantSeven.id(), DataPackageImportMode.APPEND,
                txt("phones.txt", "639171234567\n639181234567\n555555555\n"), 9L);
        service.create(new DataPackageCreateDTO("空包", null), 9L);

        TenantContext.set(8L);
        DataPackageDetailVO tenantEight = service.create(
                new DataPackageCreateDTO("同名包", null), 10L);
        TenantContext.set(TENANT_ID);

        assertThatThrownBy(() -> service.detail(tenantEight.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("数据包不存在或已删除");

        DataPackageQuery query = new DataPackageQuery();
        query.setCountryIso2s("ph,UNKNOWN,PH");
        query.setForTask(true);
        PageResult<com.armada.hyperlink.data.model.vo.DataPackageListItemVO> page = service.list(query);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.list().get(0).countries()).containsExactly("PH", null);
        assertThat(page.list().get(0).primaryCountryIso2()).isEqualTo("PH");

        DataPackageQuery unknownOnly = new DataPackageQuery();
        unknownOnly.setCountryIso2s("UNKNOWN");
        assertThat(service.list(unknownOnly).list()).isEmpty();

        jdbc().update("UPDATE data_package_phone SET pool_status = 2 WHERE tenant_id = ?", TENANT_ID);
        jdbc().update("UPDATE data_package_stat SET unused_count = 0, claimed_count = 3 "
                + "WHERE tenant_id = ?", TENANT_ID);
        assertThat(service.list(query).list()).isEmpty();
        assertThat(service.countries())
                .extracting(option -> option.value())
                .containsExactly("PH", "CN", "UNKNOWN");
        assertThat(service.countries().get(2).countryIso2()).isNull();

        DataPackageQuery uvQuery = new DataPackageQuery();
        uvQuery.setMaxUvPercent(BigDecimal.ZERO);
        assertThat(service.list(uvQuery).list())
                .hasSize(2)
                .allSatisfy(item -> assertThat(item.metrics().clickUvCount()).isZero());
        uvQuery.setMinUvPercent(new BigDecimal("0.01"));
        uvQuery.setMaxUvPercent(null);
        assertThat(service.list(uvQuery).list()).isEmpty();
    }

    @Test
    void importRejectsForbiddenCountriesBeforeWritingPhoneRows() {
        DataPackageDetailVO dataPackage = service.create(
                new DataPackageCreateDTO("禁导国家", null), 9L);

        assertThatThrownBy(() -> importService.importPhones(
                dataPackage.id(), DataPackageImportMode.APPEND,
                txt("china.txt", "8613800138000\n"), 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("禁止上传国家")
                .hasMessageContaining("中国（CN）");

        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM data_package_phone WHERE data_package_id = ?",
                Integer.class, dataPackage.id())).isZero();
        assertThat(jdbc().queryForObject(
                "SELECT status FROM data_package_import WHERE data_package_id = ?",
                Integer.class, dataPackage.id())).isEqualTo(3);
    }

    @Test
    void optimisticUpdateAndSoftDeleteAllowNameReuse() {
        DataPackageDetailVO first = service.create(new DataPackageCreateDTO("可复用", null), 9L);

        assertThatThrownBy(() -> service.update(
                first.id(), new DataPackageUpdateDTO("新名称", null, 2)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("数据包已被其他人修改，请刷新后重试");

        DataPackageDetailVO updated = service.update(
                first.id(), new DataPackageUpdateDTO("新名称", " 已复核 ", 1));
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.remark()).isEqualTo("已复核");
        service.delete(first.id(), 19L);
        assertThat(jdbc().queryForObject(
                "SELECT deleted_by FROM data_package WHERE id = ?",
                Long.class, first.id())).isEqualTo(19L);
        assertThatThrownBy(() -> service.detail(first.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("数据包不存在或已删除");
        assertThat(service.create(new DataPackageCreateDTO("新名称", null), 9L).id())
                .isNotEqualTo(first.id());
    }

    @Test
    void resetFailedRestoresOnlyRetryableFailuresToUnused() {
        DataPackageDetailVO dataPackage = service.create(
                new DataPackageCreateDTO("失败重置", null), 9L);
        importService.importPhones(dataPackage.id(), DataPackageImportMode.APPEND,
                txt("failed.txt", "123456\n123457\n123458\n"), 9L);
        jdbc().update("UPDATE data_package_phone SET pool_status = 5 WHERE phone IN ('123456', '123457')");
        jdbc().update("UPDATE data_package_phone SET pool_status = 6 WHERE phone = '123458'");
        jdbc().update("UPDATE data_package_stat SET unused_count = 0, retryable_failed_count = 2, "
                + "unregistered_count = 1 WHERE data_package_id = ?", dataPackage.id());

        assertThat(service.resetFailed(dataPackage.id())).isEqualTo(2);

        assertThat(jdbc().queryForList(
                "SELECT pool_status FROM data_package_phone ORDER BY phone", Integer.class))
                .containsExactly(1, 1, 6);
        DataPackageDetailVO refreshed = service.detail(dataPackage.id());
        assertThat(refreshed.metrics().unusedCount()).isEqualTo(2);
        assertThat(refreshed.metrics().failedCount()).isEqualTo(1);
        assertThat(refreshed.metrics().unregisteredCount()).isEqualTo(1);
    }

    @Test
    void resetFailedRollsBackPhoneRowsWhenStatisticsHaveDrifted() {
        DataPackageDetailVO dataPackage = service.create(
                new DataPackageCreateDTO("失败重置漂移", null), 9L);
        importService.importPhones(dataPackage.id(), DataPackageImportMode.APPEND,
                txt("drifted.txt", "123456\n"), 9L);
        jdbc().update("UPDATE data_package_phone SET pool_status = 5 WHERE phone = '123456'");
        jdbc().update("UPDATE data_package_stat SET unused_count = 0, retryable_failed_count = 0 "
                + "WHERE data_package_id = ?", dataPackage.id());

        assertThatThrownBy(() -> service.resetFailed(dataPackage.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("数据包统计状态已变化，请刷新后重试");
        assertThat(jdbc().queryForObject(
                "SELECT pool_status FROM data_package_phone WHERE phone = '123456'",
                Integer.class)).isEqualTo(5);
    }

    @Test
    void exportFiltersCurrentGenerationAndRejectsCrossTenantPackage() {
        DataPackageDetailVO dataPackage = service.create(
                new DataPackageCreateDTO("导出包", null), 9L);
        importService.importPhones(dataPackage.id(), DataPackageImportMode.APPEND,
                txt("export.txt", "123456\n123457\n123458\n"), 9L);
        jdbc().update("UPDATE data_package_phone SET pool_status = 3 WHERE phone = '123456'");
        jdbc().update("UPDATE data_package_phone SET pool_status = 4 WHERE phone = '123457'");
        jdbc().update("UPDATE data_package_phone SET pool_status = 6 WHERE phone = '123458'");

        DataPackageExportFile successful = service.exportPhones(
                dataPackage.id(), DataPackageUsageStatus.SUCCESS);
        assertThat(new String(successful.bytes(), StandardCharsets.UTF_8))
                .isEqualTo("123456\n123457\n");
        assertThat(successful.exportedCount()).isEqualTo(2);

        DataPackageExportFile failed = service.exportPhones(
                dataPackage.id(), DataPackageUsageStatus.FAILED);
        assertThat(new String(failed.bytes(), StandardCharsets.UTF_8))
                .isEqualTo("123458\n");

        TenantContext.set(8L);
        assertThatThrownBy(() -> service.exportPhones(
                dataPackage.id(), DataPackageUsageStatus.ALL))
                .isInstanceOf(BusinessException.class)
                .hasMessage("数据包不存在或已删除");
    }

    @Test
    void batchExportDeduplicatesPackagesAndKeepsSelectionOrder() {
        DataPackageDetailVO first = service.create(
                new DataPackageCreateDTO("批量一", null), 9L);
        DataPackageDetailVO second = service.create(
                new DataPackageCreateDTO("批量二", null), 9L);
        importService.importPhones(first.id(), DataPackageImportMode.APPEND,
                txt("first.txt", "111111\n111112\n"), 9L);
        importService.importPhones(second.id(), DataPackageImportMode.APPEND,
                txt("second.txt", "222221\n"), 9L);
        jdbc().update("UPDATE data_package_phone SET pool_status = 3 "
                + "WHERE data_package_id IN (?, ?)", first.id(), second.id());

        DataPackageExportFile result = service.exportPhones(
                List.of(second.id(), first.id(), second.id()),
                DataPackageUsageStatus.SINGLE);

        assertThat(result.exportedCount()).isEqualTo(3);
        assertThat(result.filename()).contains("批量2包_single");
        assertThat(new String(result.bytes(), StandardCharsets.UTF_8))
                .isEqualTo("222221\n111111\n111112\n");
    }

    @Test
    void clickRecordExportUsesTxtOrDetailedCsvAndRejectsCrossTenantSelection() {
        DataPackageDetailVO first = service.create(
                new DataPackageCreateDTO("点击包一", null), 9L);
        DataPackageDetailVO second = service.create(
                new DataPackageCreateDTO("点击包二", null), 9L);

        DataPackageExportFile txt = service.exportClickRecords(
                List.of(first.id(), second.id(), first.id()),
                DataPackageClickExportFormat.TXT);
        assertThat(txt.filename())
                .matches("数据包点击记录_批量2包_[0-9]{8}_[0-9]{6}\\.txt");
        assertThat(txt.contentType()).isEqualTo("text/plain;charset=UTF-8");
        assertThat(txt.exportedCount()).isZero();
        assertThat(txt.bytes()).isEmpty();

        DataPackageExportFile csv = service.exportClickRecords(
                List.of(first.id(), second.id()), DataPackageClickExportFormat.CSV);
        assertThat(csv.filename())
                .matches("数据包点击记录_批量2包_[0-9]{8}_[0-9]{6}\\.csv");
        assertThat(csv.contentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(new String(csv.bytes(), StandardCharsets.UTF_8))
                .isEqualTo("\uFEFF数据包名称,首次访问时间,操作系统,浏览器,ip,收件人手机\n");

        TenantContext.set(8L);
        assertThatThrownBy(() -> service.exportClickRecords(
                List.of(first.id()), DataPackageClickExportFormat.TXT))
                .isInstanceOf(BusinessException.class)
                .hasMessage("数据包不存在或已删除");
    }

    @Test
    void failedOverwriteKeepsOldGenerationAndCommitsFailedAuditIndependently() {
        DataPackageDetailVO dataPackage = service.create(
                new DataPackageCreateDTO("失败审计", null), 9L);
        importService.importPhones(dataPackage.id(), DataPackageImportMode.APPEND,
                txt("old.txt", "639171234567\n"), 9L);

        assertThatThrownBy(() -> importService.importPhones(
                dataPackage.id(), DataPackageImportMode.OVERWRITE,
                txt("invalid.txt", "abc\n"), 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("覆盖导入至少需要一条合法号码");

        DataPackageDetailVO after = service.detail(dataPackage.id());
        assertThat(after.currentGeneration()).isEqualTo(1);
        assertThat(after.metrics().totalCount()).isEqualTo(1);
        assertThat(jdbc().queryForObject(
                "SELECT status FROM data_package_import ORDER BY id DESC LIMIT 1",
                Integer.class)).isEqualTo(3);
        assertThat(jdbc().queryForObject(
                "SELECT failure_reason FROM data_package_import ORDER BY id DESC LIMIT 1",
                String.class)).contains("覆盖导入至少需要一条合法号码");
    }

    @Test
    void databaseFailureAfterPhoneInsertRollsBackPackagePhoneAndStatChanges() {
        DataPackageDetailVO dataPackage = service.create(
                new DataPackageCreateDTO("事务回滚", null), 9L);
        jdbc().update("DELETE FROM data_package_stat WHERE data_package_id = ?", dataPackage.id());

        assertThatThrownBy(() -> importService.importPhones(
                dataPackage.id(), DataPackageImportMode.APPEND,
                txt("rollback.txt", "639171234567\n"), 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("数据包正在导入");

        assertThat(jdbc().queryForObject(
                "SELECT phone_count FROM data_package WHERE id = ?",
                Integer.class, dataPackage.id())).isZero();
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM data_package_phone WHERE data_package_id = ?",
                Integer.class, dataPackage.id())).isZero();
        assertThat(jdbc().queryForObject(
                "SELECT status FROM data_package_import ORDER BY id DESC LIMIT 1",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void packageLimitRejectsWholeAppendWithoutPartialWrites() {
        DataPackageDetailVO dataPackage = service.create(
                new DataPackageCreateDTO("阈值", null), 9L);
        importService.importPhones(dataPackage.id(), DataPackageImportMode.APPEND,
                txt("three.txt", "123456\n123457\n123458\n"), 9L);

        assertThatThrownBy(() -> importService.importPhones(
                dataPackage.id(), DataPackageImportMode.APPEND,
                txt("too-many.txt", "123459\n"), 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("单包最多 3 条号码")
                .hasMessageContaining("当前可追加余量 0");
        assertThat(service.detail(dataPackage.id()).metrics().totalCount()).isEqualTo(3);
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM data_package_phone", Integer.class)).isEqualTo(3);
    }

    @Test
    void importWaitsForPackageRowLockThenContinuesWithoutOverwriting() throws Exception {
        DataPackageDetailVO dataPackage = service.create(
                new DataPackageCreateDTO("并发锁", null), 9L);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        try {
            Future<?> holder = executor.submit(() -> {
                TenantContext.set(TENANT_ID);
                try {
                    transactions.executeWithoutResult(status -> {
                        packageMapper.selectActiveForUpdate(dataPackage.id());
                        locked.countDown();
                        await(release);
                    });
                } finally {
                    TenantContext.clear();
                }
            });
            assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();

            Future<DataPackageImportResultVO> waiting = executor.submit(() -> {
                TenantContext.set(TENANT_ID);
                try {
                    return importService.importPhones(
                            dataPackage.id(), DataPackageImportMode.APPEND,
                            txt("waiting.txt", "639171234567\n"), 9L);
                } finally {
                    TenantContext.clear();
                }
            });
            Thread.sleep(200L);
            assertThat(waiting.isDone()).isFalse();
            release.countDown();
            holder.get(2, TimeUnit.SECONDS);
            assertThat(waiting.get(2, TimeUnit.SECONDS).acceptedRows()).isEqualTo(1);
            assertThat(service.detail(dataPackage.id()).metrics().totalCount()).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void maintenanceRecoversStaleAuditAndDeletesAtMostTwoThousandPhonesPerTransaction()
            throws Exception {
        long old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31);
        jdbc().update("INSERT INTO data_package (id, tenant_id, package_name, current_generation, "
                        + "phone_count, version, created_at, updated_at, deleted_at) "
                        + "VALUES (100, 7, '待清理', 1, 2001, 1, ?, ?, ?)", old, old, old);
        jdbc().update("INSERT INTO data_package_import (id, tenant_id, data_package_id, import_mode, "
                        + "status, source_file_name, created_at) VALUES (200, 7, 100, 1, 1, 'stale.txt', ?)",
                System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(2));
        insertPhones(2_001);

        assertThat(maintenanceService.recoverStaleImportBatch()).isEqualTo(1);
        assertThat(jdbc().queryForObject(
                "SELECT status FROM data_package_import WHERE id = 200", Integer.class)).isEqualTo(3);
        assertThat(maintenanceService.purgeExpiredPhoneBatch()).isEqualTo(2_000);
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM data_package_phone", Integer.class)).isEqualTo(1);
        assertThat(maintenanceService.purgeExpiredPhoneBatch()).isEqualTo(1);
        assertThat(maintenanceService.purgeExpiredPhoneBatch()).isZero();
    }

    @Test
    void maintenanceDeletesOnlyRetiredGenerationAfterItsOverwriteAgesThirtyDays() {
        DataPackageDetailVO dataPackage = service.create(
                new DataPackageCreateDTO("旧代清理", null), 9L);
        importService.importPhones(dataPackage.id(), DataPackageImportMode.APPEND,
                txt("old.txt", "123456\n123457\n"), 9L);
        DataPackageImportResultVO overwrite = importService.importPhones(
                dataPackage.id(), DataPackageImportMode.OVERWRITE,
                txt("new.txt", "123458\n"), 9L);
        long old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31);
        jdbc().update("UPDATE data_package_import SET finished_at = ? WHERE id = ?",
                old, overwrite.importId());

        assertThat(maintenanceService.purgeExpiredPhoneBatch()).isEqualTo(2);
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM data_package_phone WHERE generation = 1",
                Integer.class)).isZero();
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM data_package_phone WHERE generation = 2",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void reconciliationRepairsPhoneCountAndAllSixStatusCounters() {
        DataPackageDetailVO dataPackage = service.create(
                new DataPackageCreateDTO("校准", null), 9L);
        importService.importPhones(dataPackage.id(), DataPackageImportMode.APPEND,
                txt("states.txt", "123456\n123457\n123458\n"), 9L);
        jdbc().update("UPDATE data_package_phone SET pool_status = 4 WHERE phone = '123457'");
        jdbc().update("UPDATE data_package_phone SET pool_status = 6 WHERE phone = '123458'");
        jdbc().update("UPDATE data_package SET phone_count = 99 WHERE id = ?", dataPackage.id());

        maintenanceService.reconcile(dataPackage.id());

        DataPackageDetailVO repaired = service.detail(dataPackage.id());
        assertThat(repaired.metrics().totalCount()).isEqualTo(3);
        assertThat(repaired.metrics().unusedCount()).isEqualTo(1);
        assertThat(repaired.metrics().deliveredCount()).isEqualTo(1);
        assertThat(repaired.metrics().unregisteredCount()).isEqualTo(1);
        assertThat(repaired.metrics().failedCount()).isEqualTo(1);
    }

    private void createSchema() throws SQLException {
        execute("""
                CREATE TABLE data_package (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  package_name VARCHAR(128) NOT NULL,
                  remark VARCHAR(255),
                  current_generation INT NOT NULL DEFAULT 1,
                  phone_count INT NOT NULL DEFAULT 0,
                  version INT NOT NULL DEFAULT 1,
                  created_by BIGINT,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  deleted_by BIGINT,
                  deleted_at BIGINT,
                  is_active TINYINT GENERATED ALWAYS AS
                    (CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END),
                  UNIQUE (tenant_id, package_name, is_active)
                )
                """);
        execute("""
                CREATE TABLE data_package_phone (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  data_package_id BIGINT NOT NULL,
                  generation INT NOT NULL,
                  source_import_id BIGINT NOT NULL,
                  phone VARCHAR(32) NOT NULL,
                  country_iso2 CHAR(2),
                  pool_status TINYINT NOT NULL DEFAULT 1,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  UNIQUE (tenant_id, data_package_id, generation, phone)
                )
                """);
        execute("""
                CREATE TABLE data_package_stat (
                  data_package_id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  generation INT NOT NULL,
                  unused_count INT NOT NULL DEFAULT 0,
                  claimed_count INT NOT NULL DEFAULT 0,
                  sent_count INT NOT NULL DEFAULT 0,
                  delivered_count INT NOT NULL DEFAULT 0,
                  retryable_failed_count INT NOT NULL DEFAULT 0,
                  unregistered_count INT NOT NULL DEFAULT 0,
                  updated_at BIGINT NOT NULL,
                  reconciled_at BIGINT,
                  UNIQUE (tenant_id, data_package_id)
                )
                """);
        execute("""
                CREATE TABLE data_package_import (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  data_package_id BIGINT NOT NULL,
                  generation INT,
                  import_mode TINYINT NOT NULL,
                  status TINYINT NOT NULL,
                  source_file_name VARCHAR(255) NOT NULL,
                  total_rows INT NOT NULL DEFAULT 0,
                  accepted_rows INT NOT NULL DEFAULT 0,
                  invalid_rows INT NOT NULL DEFAULT 0,
                  duplicated_rows INT NOT NULL DEFAULT 0,
                  failure_reason VARCHAR(512),
                  created_by BIGINT,
                  created_at BIGINT NOT NULL,
                  finished_at BIGINT
                )
                """);
    }

    private void insertPhones(int count) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO data_package_phone (
                       tenant_id, data_package_id, generation, source_import_id,
                       phone, pool_status, created_at, updated_at
                     ) VALUES (7, 100, 1, 200, ?, 1, 1, 1)
                     """)) {
            for (int index = 0; index < count; index++) {
                statement.setString(1, String.valueOf(1_000_000L + index));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static MockMultipartFile txt(String name, String content) {
        return new MockMultipartFile(
                "file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    private static CountryOptionVO country(String iso2, String nameZh, String prefix) {
        return new CountryOptionVO(iso2, iso2, nameZh, nameZh, prefix, "", false, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发测试释放超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试被中断", exception);
        }
    }

    /** 只装配生产 Mapper XML、租户插件和本功能 Service 的 H2 测试上下文。 */
    @Configuration
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:hyperlink_data;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(
                    new ClassPathResource("mapper/hyperlink/data/DataPackageMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/data/DataPackagePhoneMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/data/DataPackageStatMapper.xml"),
                    new ClassPathResource("mapper/hyperlink/data/DataPackageImportMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        DataPackageMapper dataPackageMapper(SqlSessionTemplate template) {
            return template.getMapper(DataPackageMapper.class);
        }

        @Bean
        DataPackagePhoneMapper dataPackagePhoneMapper(SqlSessionTemplate template) {
            return template.getMapper(DataPackagePhoneMapper.class);
        }

        @Bean
        DataPackageStatMapper dataPackageStatMapper(SqlSessionTemplate template) {
            return template.getMapper(DataPackageStatMapper.class);
        }

        @Bean
        DataPackageImportMapper dataPackageImportMapper(SqlSessionTemplate template) {
            return template.getMapper(DataPackageImportMapper.class);
        }

        @Bean
        CountryService countryService() {
            return new TestCountryService();
        }

        @Bean
        DataPackageConverter dataPackageConverter() {
            return new DataPackageConverter() { };
        }

        @Bean
        DataPackageTxtParser dataPackageTxtParser() {
            return new DataPackageTxtParser();
        }

        @Bean
        DataPackageService dataPackageService(
                DataPackageMapper mapper,
                DataPackagePhoneMapper phoneMapper,
                DataPackageStatMapper statMapper,
                CountryService countryService,
                DataPackageConverter converter) {
            return new DataPackageServiceImpl(
                    mapper, phoneMapper, statMapper, countryService, converter);
        }

        @Bean
        DataPackageImportService dataPackageImportService(
                DataPackageMapper mapper,
                DataPackagePhoneMapper phoneMapper,
                DataPackageStatMapper statMapper,
                DataPackageImportMapper importMapper,
                DataPackageTxtParser parser,
                CountryService countryService,
                PlatformTransactionManager transactionManager) {
            return new DataPackageImportServiceImpl(
                    mapper, phoneMapper, statMapper, importMapper, parser,
                    countryService, transactionManager, 3);
        }

        @Bean
        DataPackageMaintenanceService dataPackageMaintenanceService(
                DataPackageMapper mapper,
                DataPackagePhoneMapper phoneMapper,
                DataPackageStatMapper statMapper,
                DataPackageImportMapper importMapper) {
            return new DataPackageMaintenanceServiceImpl(
                    mapper, phoneMapper, statMapper, importMapper,
                    30, 2_000, 60_000L, 2_000);
        }
    }

    /** 不依赖 Mockito agent 的确定性国家主数据测试桩。 */
    private static final class TestCountryService implements CountryService {

        private static final CountryOptionVO PHILIPPINES = country("PH", "菲律宾", "+63");
        private static final CountryOptionVO CHINA = country("CN", "中国", "+86");

        @Override
        public CountryOptionsVO options(String scope) {
            return new CountryOptionsVO(List.of(PHILIPPINES, CHINA));
        }

        @Override
        public Map<String, CountryOptionVO> resolveActiveOptionsByPhonePrefix(
                Collection<String> phones) {
            Map<String, CountryOptionVO> result = new LinkedHashMap<>();
            PhonePrefixResolver resolver = activePhonePrefixResolver();
            for (String phone : phones) {
                CountryOptionVO country = resolver.resolve(phone);
                if (country != null) {
                    result.put(phone, country);
                }
            }
            return result;
        }

        @Override
        public PhonePrefixResolver activePhonePrefixResolver() {
            return phone -> {
                if (phone.startsWith("63")) {
                    return PHILIPPINES;
                }
                return phone.startsWith("86") ? CHINA : null;
            };
        }

        @Override
        public String resolveIpRegion(String value) {
            throw unused();
        }

        @Override
        public String resolveIpRegionByPhonePrefix(String wsPhone) {
            throw unused();
        }

        @Override
        public Map<String, String> resolveIpRegionsByPhonePrefix(Collection<String> wsPhones) {
            throw unused();
        }

        @Override
        public Map<String, CountryReferenceVO> resolveActiveCountriesByPhoneNumbers(
                Collection<String> wsPhones) {
            throw unused();
        }

        @Override
        public String resolveIpRegionByIso2(String iso2) {
            throw unused();
        }

        @Override
        public CountryOptionVO requireActiveOption(String value, boolean mixedAllowed) {
            throw unused();
        }

        @Override
        public Map<String, CountryOptionVO> optionsByValues(Collection<String> values) {
            throw unused();
        }

        @Override
        public CountryReferenceVO requireActiveReference(Long countryId) {
            throw unused();
        }

        @Override
        public Map<Long, CountryReferenceVO> referencesByIds(Collection<Long> countryIds) {
            throw unused();
        }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("该国家服务方法不属于数据包测试范围");
        }
    }
}
