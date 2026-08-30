package com.armada.marketing.asset.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.marketing.asset.model.dto.ResourceAssetQuery;
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
class ResourceAssetMapperH2Test {

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
        assertThat(tagMapper.selectActiveTagNames()).containsExactly("Promo", "promo");
        assertThat(tagMapper.selectRelationsByFileIds(List.of(current.getId(), other.getId())))
                .allSatisfy(relation -> assertThat(relation.fileId()).isEqualTo(current.getId()));
        assertThat(fileMapper.selectAssetMetadataById(other.getId())).isNull();
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
        assertThat(fileMapper.selectAssetMetadataById(current.getId()).getAssetName()).isEqualTo("已编辑");
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
                    width INT,
                    height INT,
                    created_by BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT,
                    deleted_at BIGINT
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
                    new ClassPathResource("mapper/marketing/ResourceAssetTagMapper.xml"));
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
        ResourceAssetTagMapper tagMapper(SqlSessionTemplate template) {
            return template.getMapper(ResourceAssetTagMapper.class);
        }
    }
}
