package com.armada.marketing.asset.mapper;

import com.armada.marketing.asset.model.enums.ResourceAssetScope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.marketing.asset.model.dto.ResourceAssetQuery;
import com.armada.marketing.asset.model.dto.ResourceAssetMoveDTO;
import com.armada.marketing.asset.service.ResourceAssetGroupService;
import com.armada.marketing.asset.service.ResourceAssetWriteService;
import com.armada.marketing.asset.model.vo.ResourceAssetGroupVO;
import com.armada.shared.exception.BusinessException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import com.armada.marketing.asset.model.entity.ResourceAssetTag;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 素材库生产 Mapper XML、租户插件、筛选和锁查询的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(ResourceAssetMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
public class ResourceAssetMapperH2Test {

    private static final long TENANT_ID = 7L;
    private static final long OTHER_TENANT_ID = 8L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MarketingTemplateFileMapper fileMapper;

    @Autowired
    private ResourceAssetTagMapper tagMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ResourceAssetGroupService groupService;

    @Autowired
    private ResourceAssetWriteService writeService;

    @Test
    void legacyAssetsRemainVisibleInBothScopesAndNewUploadsStaySeparate() {
        var legacy = insertFile("历史图片", 100L, new byte[] {1});
        var hyperlink = fileMapper.selectById(legacy.getId());
        hyperlink.setId(null);
        hyperlink.setAssetScope(1);
        fileMapper.insert(hyperlink);
        var script = fileMapper.selectById(legacy.getId());
        script.setId(null);
        script.setAssetScope(2);
        fileMapper.insert(script);
        var query = query();
        assertThat(fileMapper.selectAssetPage(query)).extracting(MarketingTemplateFile::getId)
                .containsExactly(hyperlink.getId(), legacy.getId());
        query.setScope(com.armada.marketing.asset.model.enums.ResourceAssetScope.SCRIPT);
        assertThat(fileMapper.selectAssetPage(query)).extracting(MarketingTemplateFile::getId)
                .containsExactly(script.getId(), legacy.getId());
    }

    @Test
    void sharedHistoricalImageHasIndependentGroupsPerBusiness() {
        var legacy = insertFile("历史共享图片", 100L, new byte[] {1});
        var hyperlinkGroup = groupService.create("活动", ResourceAssetScope.HYPERLINK);
        var scriptGroup = groupService.create("活动", ResourceAssetScope.SCRIPT);
        groupService.move(new ResourceAssetMoveDTO(List.of(legacy.getId()), hyperlinkGroup.id()), ResourceAssetScope.HYPERLINK);
        groupService.move(new ResourceAssetMoveDTO(List.of(legacy.getId()), scriptGroup.id()), ResourceAssetScope.SCRIPT);
        assertThat(fileMapper.selectAssetMetadataById(legacy.getId(), 1).getGroupId()).isEqualTo(hyperlinkGroup.id());
        assertThat(fileMapper.selectAssetMetadataById(legacy.getId(), 2).getGroupId()).isEqualTo(scriptGroup.id());
        assertThat(groupService.list(ResourceAssetScope.HYPERLINK)).extracting(ResourceAssetGroupVO::id).containsExactly(hyperlinkGroup.id());
        assertThat(groupService.list(ResourceAssetScope.SCRIPT)).extracting(ResourceAssetGroupVO::id).containsExactly(scriptGroup.id());
        assertThatThrownBy(() -> groupService.delete(scriptGroup.id(), ResourceAssetScope.HYPERLINK)).isInstanceOf(BusinessException.class);
        groupService.delete(hyperlinkGroup.id(), ResourceAssetScope.HYPERLINK);
        assertThat(fileMapper.selectAssetMetadataById(legacy.getId(), 1).getGroupId()).isNull();
        assertThat(fileMapper.selectAssetMetadataById(legacy.getId(), 2).getGroupId()).isEqualTo(scriptGroup.id());
        assertThat(fileMapper.selectById(legacy.getId()).getAssetScope()).isNull();
    }

    @Test
    void newScriptAssetIsAbsentFromHyperlinkTagsDetailsAndWrites() {
        var legacy = insertFile("旧图", 100L, new byte[] {1});
        addTag(legacy.getId(), "历史标签", 100L);
        var upload = fileMapper.selectById(legacy.getId());
        upload.setId(null);
        Long scriptId = writeService.create(upload, List.of("养群标签"), ResourceAssetScope.SCRIPT);
        assertThat(fileMapper.selectAssetMetadataById(scriptId, 1)).isNull();
        assertThat(fileMapper.selectAssetMetadataById(scriptId, 2)).isNotNull();
        assertThat(tagMapper.selectActiveTagNames(1)).containsExactly("历史标签");
        assertThat(tagMapper.selectActiveTagNames(2)).containsExactlyInAnyOrder("历史标签", "养群标签");
        assertThatThrownBy(() -> writeService.update(scriptId, "跨业务编辑", List.of(), 200L, ResourceAssetScope.HYPERLINK))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> writeService.delete(scriptId, 200L, ResourceAssetScope.HYPERLINK))
                .isInstanceOf(BusinessException.class);
        var group = groupService.create("超链分组", ResourceAssetScope.HYPERLINK);
        assertThatThrownBy(() -> groupService.move(new ResourceAssetMoveDTO(List.of(legacy.getId(), scriptId), group.id()), ResourceAssetScope.HYPERLINK))
                .isInstanceOf(BusinessException.class);
        assertThat(fileMapper.selectAssetMetadataById(legacy.getId(), 1).getGroupId()).isNull();
        assertThat(fileMapper.selectById(scriptId).getDeletedAt()).isNull();
    }

    @Autowired
    private com.armada.marketing.service.MarketingTemplateFileService bindingService;

    @Test
    void bindingAcceptsLegacyInBothBusinessesAndRejectsNewCrossBusinessIds() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        var legacy = insertFile("历史图", 100L, out.toByteArray());
        execute("UPDATE marketing_template_file SET content_type='image/png' WHERE id=" + legacy.getId());
        var script = fileMapper.selectById(legacy.getId());
        script.setId(null);
        script.setAssetScope(2);
        fileMapper.insert(script);
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            bindingService.lockAndValidateBindableAssets(List.of(legacy.getId()), ResourceAssetScope.HYPERLINK);
            bindingService.lockAndValidateBindableAssets(List.of(legacy.getId(), script.getId()), ResourceAssetScope.SCRIPT);
            assertThat(bindingService.lockContentForBinding(legacy.getId(), ResourceAssetScope.HYPERLINK).content()).isNotEmpty();
        });
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                bindingService.lockAndValidateBindableAssets(List.of(script.getId()), ResourceAssetScope.HYPERLINK)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                bindingService.lockContentForBinding(script.getId(), ResourceAssetScope.HYPERLINK)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deletingGroupPreservesImageBytesTagsAndReferences() throws SQLException {
        var group = groupService.create(" 活动图片 ", ResourceAssetScope.HYPERLINK);
        var image = insertFile("主图", 100L, new byte[] {1, 2});
        addTag(image.getId(), "Promo", 100L);
        execute("INSERT INTO marketing_template (tenant_id,image_file_id) VALUES (7," + image.getId() + ")");
        groupService.move(new ResourceAssetMoveDTO(List.of(image.getId()), group.id()), ResourceAssetScope.HYPERLINK);
        assertThat(fileMapper.selectAssetMetadataById(image.getId(), 1).getGroupId()).isEqualTo(group.id());
        groupService.delete(group.id(), ResourceAssetScope.HYPERLINK);
        assertThat(groupService.list(ResourceAssetScope.HYPERLINK)).isEmpty();
        var saved = fileMapper.selectById(image.getId());
        assertThat(saved.getGroupId()).isNull();
        assertThat(saved.getContent()).containsExactly(1, 2);
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(fileMapper.countReferences(TENANT_ID, image.getId())).isEqualTo(1);
        assertThat(tagMapper.selectActiveTagNames(1)).containsExactly("Promo");
    }

    @Test
    void invalidBatchAndForeignGroupCannotChangeAnyAsset() {
        var group = groupService.create("当前分组", ResourceAssetScope.HYPERLINK);
        var image = insertFile("当前", 100L, new byte[] {1});
        TenantContext.set(OTHER_TENANT_ID);
        var foreignGroup = groupService.create("其他分组", ResourceAssetScope.HYPERLINK);
        var foreign = insertFile("其他", 100L, new byte[] {2});
        TenantContext.set(TENANT_ID);
        assertThatThrownBy(() -> groupService.move(new ResourceAssetMoveDTO(
                List.of(image.getId(), foreign.getId()), group.id()), ResourceAssetScope.HYPERLINK)).isInstanceOf(BusinessException.class);
        assertThat(fileMapper.selectAssetMetadataById(image.getId(), 1).getGroupId()).isNull();
        assertThatThrownBy(() -> groupService.delete(foreignGroup.id(), ResourceAssetScope.HYPERLINK)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> groupService.move(new ResourceAssetMoveDTO(
                List.of(image.getId()), foreignGroup.id()), ResourceAssetScope.HYPERLINK)).isInstanceOf(BusinessException.class);
        assertThat(groupService.list(ResourceAssetScope.HYPERLINK)).extracting(ResourceAssetGroupVO::id).containsExactly(group.id());
        TenantContext.set(OTHER_TENANT_ID);
        assertThat(groupService.list(ResourceAssetScope.HYPERLINK)).extracting(ResourceAssetGroupVO::id).containsExactly(foreignGroup.id());
    }

    @Test
    void groupNamesAreRequiredAndUniqueWithinTenant() {
        groupService.create("活动", ResourceAssetScope.HYPERLINK);
        assertThatThrownBy(() -> groupService.create(" 活动 ", ResourceAssetScope.HYPERLINK)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> groupService.create(" ", ResourceAssetScope.HYPERLINK)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> groupService.create("a".repeat(65), ResourceAssetScope.HYPERLINK)).isInstanceOf(BusinessException.class);
        TenantContext.set(OTHER_TENANT_ID);
        assertThat(groupService.create("活动", ResourceAssetScope.HYPERLINK).id()).isNotNull();
    }

    @Test
    void uploadIntoDeletedGroupFailsWithoutCreatingFile() {
        var group = groupService.create("上传目标", ResourceAssetScope.HYPERLINK);
        groupService.delete(group.id(), ResourceAssetScope.HYPERLINK);
        MarketingTemplateFile file = new MarketingTemplateFile();
        file.setGroupId(group.id());
        assertThatThrownBy(() -> writeService.create(file, List.of(), ResourceAssetScope.HYPERLINK)).isInstanceOf(BusinessException.class);
        assertThat(fileMapper.countAssetPage(query())).isZero();
    }

    @Test
    void uploadStoresGroupAndDeleteRollbackRestoresBothGroupAndMembership() {
        var group = groupService.create("保留分组", ResourceAssetScope.HYPERLINK);
        var original = insertFile("原图片", 100L, new byte[] {1});
        var upload = fileMapper.selectById(original.getId());
        upload.setId(null);
        upload.setGroupId(group.id());
        Long uploadedId = writeService.create(upload, List.of(), ResourceAssetScope.HYPERLINK);
        assertThat(fileMapper.selectAssetMetadataById(uploadedId, 1).getGroupId()).isEqualTo(group.id());
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            groupService.delete(group.id(), ResourceAssetScope.HYPERLINK);
            throw new IllegalStateException("simulate rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(groupService.list(ResourceAssetScope.HYPERLINK)).extracting(ResourceAssetGroupVO::id).containsExactly(group.id());
        assertThat(fileMapper.selectAssetMetadataById(uploadedId, 1).getGroupId()).isEqualTo(group.id());
    }

    @Test
    void deleteAndConcurrentMoveSerializeOnGroupLock() throws Exception {
        var group = groupService.create("并发分组", ResourceAssetScope.HYPERLINK);
        var image = insertFile("图片", 100L, new byte[] {1});
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var started = new java.util.concurrent.CountDownLatch(1);
        var worker = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                groupService.lockTarget(group.id(), ResourceAssetScope.HYPERLINK);
                worker.set(executor.submit(() -> {
                    TenantContext.set(TENANT_ID);
                    try {
                        started.countDown();
                        groupService.move(new ResourceAssetMoveDTO(List.of(image.getId()), group.id()), ResourceAssetScope.HYPERLINK);
                    } finally {
                        TenantContext.clear();
                    }
                }));
                try {
                    assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> worker.get().get(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                            .isInstanceOf(java.util.concurrent.TimeoutException.class);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                groupService.delete(group.id(), ResourceAssetScope.HYPERLINK);
            });
            assertThatThrownBy(() -> worker.get().get(3, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BusinessException.class);
            assertThat(fileMapper.selectAssetMetadataById(image.getId(), 1).getGroupId()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void groupFilterSeparatesUngroupedAssets() throws SQLException {
        var grouped = insertFile("分组图片", 100L, new byte[] {1});
        var ungrouped = insertFile("未分组图片", 200L, new byte[] {2});
        var group = groupService.create("测试分组", ResourceAssetScope.HYPERLINK);
        groupService.move(new ResourceAssetMoveDTO(List.of(grouped.getId()), group.id()), ResourceAssetScope.HYPERLINK);
        var query = query();
        query.setGroupId(group.id());
        assertThat(fileMapper.countAssetPage(query)).isEqualTo(1);
        assertThat(fileMapper.selectAssetPage(query)).extracting(MarketingTemplateFile::getId)
                .containsExactly(grouped.getId());
        query.setGroupId(0L);
        assertThat(fileMapper.selectAssetPage(query)).extracting(MarketingTemplateFile::getId)
                .containsExactly(ungrouped.getId());
    }

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        resetSchema();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void scriptStepAssetReferenceIsCountedOncePerTaskAndNeverAcrossTenants() throws SQLException {
        var file = insertFile("script image", 100L, new byte[] {1});
        String steps = "[{\"message\":{\"imageFileId\":" + file.getId() + "}},"
                + "{\"message\":{\"imageFileId\":" + file.getId() + "}}]";
        execute("INSERT INTO script_marketing_task VALUES (1, 7, '" + steps + "')");
        execute("INSERT INTO script_marketing_task VALUES (2, 8, '" + steps + "')");
        assertThat(fileMapper.countReferences(7L, file.getId())).isEqualTo(1);
        assertThat(fileMapper.selectReferenceCounts(7L, List.of(file.getId())))
                .singleElement().satisfies(row -> assertThat(row.referenceCount()).isEqualTo(1));
    }

    /** 定义软删除只解除定义引用，已经复制到任务的图片引用继续保护公共素材。 */
    @Test
    void scriptDefinitionRetainsSharedImageUntilDeletedAndKeepsTaskSnapshotReference() throws SQLException {
        var file = insertFile("shared script image", 100L, new byte[] {1});
        String steps = "[{\"message\":{\"imageFileId\":" + file.getId() + "}}]";
        execute("INSERT INTO script_marketing_definition VALUES (1,7,'" + steps + "',NULL)");
        execute("INSERT INTO script_marketing_definition VALUES (2,8,'" + steps + "',NULL)");
        assertThat(fileMapper.countReferences(7L, file.getId())).isEqualTo(1);
        execute("INSERT INTO script_marketing_task VALUES (1,7,'" + steps + "')");
        assertThat(fileMapper.countReferences(7L, file.getId())).isEqualTo(2);
        execute("UPDATE script_marketing_definition SET deleted_at=999 WHERE tenant_id=7");
        assertThat(fileMapper.countReferences(7L, file.getId())).isEqualTo(1);
        assertThat(fileMapper.selectReferenceCounts(7L, List.of(file.getId())))
                .singleElement().satisfies(row -> assertThat(row.referenceCount()).isEqualTo(1));
    }

    /** H2 方言适配：本查询只使用数组中 message.imageFileId 的包含条件。 */
    public static int scriptJsonContains(String document, String candidate) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var expected = json.readTree(candidate).path("message").path("imageFileId");
        for (var step : json.readTree(document)) {
            if (step.path("message").path("imageFileId").equals(expected)) return 1;
        }
        return 0;
    }

    @Test
    void listUsesAnyTagMatchStableSortAndNeverReturnsBlob() {
        MarketingTemplateFile oldest = insertFile("旧活动", 100L, new byte[] {1, 2, 3});
        MarketingTemplateFile latestLowId = insertFile("暑期主图", 200L, new byte[] {4, 5, 6});
        MarketingTemplateFile latestHighId = insertFile("暑期尾图", 200L, new byte[] {7, 8, 9});
        addTag(oldest.getId(), "Archive", 100L);
        addTag(latestLowId.getId(), "Promo", 200L);
        addTag(latestHighId.getId(), "New", 200L);

        ResourceAssetQuery query = query();
        query.setAssetName("暑期");
        query.setTags(List.of("Promo", "New"));

        assertThat(fileMapper.countAssetPage(query)).isEqualTo(2);
        assertThat(fileMapper.selectAssetPage(query))
                .extracting(MarketingTemplateFile::getId)
                .containsExactly(latestHighId.getId(), latestLowId.getId());
        assertThat(fileMapper.selectAssetPage(query))
                .allSatisfy(file -> assertThat(file.getContent()).isNull());
    }

    @Test
    void tagNamesAreCaseSensitiveAndRelationsStayInsideTenant() {
        MarketingTemplateFile current = insertFile("当前租户", 100L, new byte[] {1});
        addTag(current.getId(), "Promo", 100L);
        addTag(current.getId(), "promo", 101L);

        TenantContext.set(OTHER_TENANT_ID);
        MarketingTemplateFile other = insertFile("其他租户", 200L, new byte[] {2});
        addTag(other.getId(), "Hidden", 200L);
        TenantContext.set(TENANT_ID);

        ResourceAssetQuery upper = query();
        upper.setTags(List.of("Promo"));
        ResourceAssetQuery lower = query();
        lower.setTags(List.of("promo"));

        assertThat(fileMapper.selectAssetPage(upper))
                .extracting(MarketingTemplateFile::getId)
                .containsExactly(current.getId());
        assertThat(fileMapper.selectAssetPage(lower))
                .extracting(MarketingTemplateFile::getId)
                .containsExactly(current.getId());
        assertThat(tagMapper.selectActiveTagNames(1)).containsExactly("Promo", "promo");
        assertThat(tagMapper.selectRelationsByFileIds(List.of(current.getId(), other.getId())))
                .allSatisfy(relation -> assertThat(relation.fileId()).isEqualTo(current.getId()));
        assertThat(fileMapper.selectAssetMetadataById(other.getId(), 1)).isNull();
    }

    @Test
    void tenantPluginProtectsMetadataUpdatesAndSoftDeletes() {
        MarketingTemplateFile current = insertFile("可编辑", 100L, new byte[] {1});
        TenantContext.set(OTHER_TENANT_ID);
        MarketingTemplateFile other = insertFile("不可编辑", 100L, new byte[] {2});
        TenantContext.set(TENANT_ID);

        assertThat(fileMapper.updateAssetMetadata(other.getId(), "越权名称", 300L)).isZero();
        assertThat(fileMapper.softDeleteAsset(other.getId(), 300L)).isZero();
        assertThat(fileMapper.updateAssetMetadata(current.getId(), "已编辑", 300L)).isEqualTo(1);
        assertThat(fileMapper.selectAssetMetadataById(current.getId(), 1).getAssetName()).isEqualTo("已编辑");
    }

    @Test
    void referenceCountsDeduplicateTwoSlotsOfOneTemplateOrTask() throws SQLException {
        MarketingTemplateFile file = insertFile("被引用", 100L, new byte[] {1});
        execute("""
                INSERT INTO marketing_template (tenant_id, image_file_id, deleted_at)
                VALUES (7, %d, NULL), (8, %d, NULL)
                """.formatted(file.getId(), file.getId()));
        execute("""
                INSERT INTO hyperlink_template
                    (tenant_id, link_preview_asset_id, body_main_asset_id, deleted_at)
                VALUES
                    (7, %d, %d, NULL),
                    (8, %d, %d, NULL)
                """.formatted(file.getId(), file.getId(), file.getId(), file.getId()));
        execute("""
                INSERT INTO hyperlink_task_content
                    (hyperlink_task_id, tenant_id, link_preview_asset_id, body_main_asset_id)
                VALUES
                    (101, 7, %d, %d),
                    (102, 8, %d, %d)
                """.formatted(file.getId(), file.getId(), file.getId(), file.getId()));

        assertThat(fileMapper.countReferences(TENANT_ID, file.getId())).isEqualTo(3);
        assertThat(fileMapper.selectReferenceCounts(TENANT_ID, List.of(file.getId())))
                .singleElement()
                .satisfies(count -> {
                    assertThat(count.assetId()).isEqualTo(file.getId());
                    assertThat(count.referenceCount()).isEqualTo(3);
                });
    }

    @Test
    void globalTenantLockQueriesRunInsideRealSpringTransaction() {
        MarketingTemplateFile current = insertFile("待锁定", 100L, new byte[] {1});
        TenantContext.set(OTHER_TENANT_ID);
        MarketingTemplateFile other = insertFile("其他租户", 100L, new byte[] {2});
        TenantContext.set(TENANT_ID);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            assertThat(fileMapper.selectByIdForUpdate(current.getId())).isNotNull();
            assertThat(fileMapper.selectIdByIdForUpdate(current.getId())).isEqualTo(current.getId());
            assertThat(fileMapper.selectByIdForUpdate(other.getId())).isNull();
            assertThat(fileMapper.selectIdByIdForUpdate(other.getId())).isNull();

            TenantContext.set(OTHER_TENANT_ID);
            assertThat(fileMapper.selectByIdForUpdate(other.getId())).isNotNull();
            assertThat(fileMapper.selectIdByIdForUpdate(other.getId())).isEqualTo(other.getId());
            assertThat(fileMapper.selectByIdForUpdate(current.getId())).isNull();
            assertThat(fileMapper.selectIdByIdForUpdate(current.getId())).isNull();
            TenantContext.set(TENANT_ID);
        });
    }

    private ResourceAssetQuery query() {
        ResourceAssetQuery query = new ResourceAssetQuery();
        query.setPage(1);
        query.setPageSize(24);
        return query;
    }

    @Test
    void selectableAssetsIncludePngAndJpegWithinSizeLimitAndTenant() throws SQLException {
        var jpeg = insertFile("JPEG", 100L, new byte[] {1});
        var png = insertFile("PNG", 200L, new byte[] {2});
        var oversized = insertFile("oversized PNG", 300L, new byte[] {3});
        var gif = insertFile("GIF", 400L, new byte[] {4});
        execute("UPDATE marketing_template_file SET content_type='IMAGE/PNG', size_bytes=512000 WHERE id=" + png.getId());
        execute("UPDATE marketing_template_file SET content_type='image/png', size_bytes=512001 WHERE id=" + oversized.getId());
        execute("UPDATE marketing_template_file SET content_type='image/gif' WHERE id=" + gif.getId());
        TenantContext.set(OTHER_TENANT_ID);
        var other = insertFile("other PNG", 500L, new byte[] {5});
        execute("UPDATE marketing_template_file SET content_type='image/png' WHERE id=" + other.getId());
        TenantContext.set(TENANT_ID);
        ResourceAssetQuery query = query();
        query.setSelectableOnly(true);

        assertThat(fileMapper.countAssetPage(query)).isEqualTo(2);
        assertThat(fileMapper.selectAssetPage(query)).extracting(MarketingTemplateFile::getId)
                .containsExactly(png.getId(), jpeg.getId());
        fileMapper.softDeleteAsset(png.getId(), 600L);
        assertThat(fileMapper.countAssetPage(query)).isEqualTo(1);
        assertThat(fileMapper.selectAssetPage(query)).extracting(MarketingTemplateFile::getId)
                .containsExactly(jpeg.getId());
    }

    private MarketingTemplateFile insertFile(String name, long createdAt, byte[] content) {
        MarketingTemplateFile file = new MarketingTemplateFile();
        file.setOriginalFilename(name + ".jpg");
        file.setContentType("image/jpeg");
        file.setSizeBytes((long) content.length);
        file.setContent(content);
        file.setAssetName(name);
        file.setWidth(100);
        file.setHeight(80);
        file.setCreatedBy(11L);
        file.setCreatedAt(createdAt);
        file.setUpdatedAt(createdAt);
        assertThat(fileMapper.insert(file)).isEqualTo(1);
        return file;
    }

    private void addTag(Long fileId, String name, long createdAt) {
        ResourceAssetTag tag = new ResourceAssetTag();
        tag.setTagName(name);
        tag.setCreatedAt(createdAt);
        tagMapper.insertIgnore(tag);
        ResourceAssetTag stored = tagMapper.selectByNames(List.of(name)).get(0);
        tagMapper.insertRefIgnore(fileId, stored.getId(), createdAt);
    }

    private void resetSchema() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("CREATE TABLE script_marketing_task (id BIGINT PRIMARY KEY, tenant_id BIGINT, steps_json LONGTEXT)");
        execute("CREATE TABLE script_marketing_definition (id BIGINT PRIMARY KEY, tenant_id BIGINT, steps_json LONGTEXT, deleted_at BIGINT)");
        execute("CREATE ALIAS JSON_CONTAINS FOR '" + ResourceAssetMapperH2Test.class.getName() + ".scriptJsonContains'");
        execute("""
                CREATE TABLE marketing_template_file (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    original_filename VARCHAR(255) NOT NULL,
                    content_type VARCHAR(128) NOT NULL,
                    size_bytes BIGINT NOT NULL,
                    content BLOB NOT NULL,
                    owner_user_id BIGINT,
                    asset_name VARCHAR(128),
                    asset_scope TINYINT,
                    width INT,
                    height INT,
                    created_by BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT,
                    deleted_at BIGINT
                )
                """);
        execute("""
                CREATE TABLE resource_asset_group_ref (
                    tenant_id BIGINT NOT NULL, file_id BIGINT NOT NULL,
                    scope TINYINT NOT NULL, group_id BIGINT NOT NULL, created_at BIGINT NOT NULL,
                    PRIMARY KEY (tenant_id, file_id, scope)
                )
                """);
        execute("""
                CREATE TABLE resource_asset_group (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_name VARCHAR(64) NOT NULL,
                    scope TINYINT NOT NULL,
                    created_at BIGINT NOT NULL,
                    UNIQUE (tenant_id, scope, group_name)
                )
                """);
        execute("""
                CREATE TABLE resource_asset_tag (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    tag_name VARCHAR(64) NOT NULL,
                    created_at BIGINT NOT NULL,
                    UNIQUE (tenant_id, tag_name)
                )
                """);
        execute("""
                CREATE TABLE resource_asset_tag_ref (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    file_id BIGINT NOT NULL,
                    resource_asset_tag_id BIGINT NOT NULL,
                    created_at BIGINT NOT NULL,
                    UNIQUE (tenant_id, file_id, resource_asset_tag_id)
                )
                """);
        execute("""
                CREATE TABLE marketing_template (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    image_file_id BIGINT,
                    deleted_at BIGINT
                )
                """);
        execute("""
                CREATE TABLE hyperlink_template (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    link_preview_asset_id BIGINT,
                    body_main_asset_id BIGINT,
                    deleted_at BIGINT
                )
                """);
        execute("""
                CREATE TABLE hyperlink_task_content (
                    hyperlink_task_id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    link_preview_asset_id BIGINT,
                    body_main_asset_id BIGINT
                )
                """);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** H2、生产 XML、租户插件和 Spring 事务管理器测试配置。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:resource_asset_mapper_test;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
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
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setUseGeneratedKeys(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(
                    new ClassPathResource("mapper/marketing/MarketingTemplateFileMapper.xml"),
                    new ClassPathResource("mapper/marketing/ResourceAssetTagMapper.xml"),
                    new ClassPathResource("mapper/marketing/ResourceAssetGroupMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        MarketingTemplateFileMapper fileMapper(SqlSessionTemplate template) {
            return template.getMapper(MarketingTemplateFileMapper.class);
        }

        @Bean
        ResourceAssetGroupMapper groupMapper(SqlSessionTemplate template) {
            return template.getMapper(ResourceAssetGroupMapper.class);
        }

        @Bean
        com.armada.marketing.service.MarketingTemplateFileService bindingService(MarketingTemplateFileMapper files) {
            return new com.armada.marketing.service.impl.MarketingTemplateFileServiceImpl(files);
        }

        @Bean
        ResourceAssetGroupService groupService(ResourceAssetGroupMapper groups, MarketingTemplateFileMapper files) {
            return new ResourceAssetGroupService(groups, files);
        }

        @Bean
        ResourceAssetWriteService writeService(MarketingTemplateFileMapper files, ResourceAssetTagMapper tags,
                                               ResourceAssetGroupService groups) {
            return new ResourceAssetWriteService(files, tags, groups);
        }

        @Bean
        ResourceAssetTagMapper tagMapper(SqlSessionTemplate template) {
            return template.getMapper(ResourceAssetTagMapper.class);
        }
    }
}
