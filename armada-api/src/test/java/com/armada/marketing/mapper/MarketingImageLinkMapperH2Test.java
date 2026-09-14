package com.armada.marketing.mapper;

import com.armada.boot.config.MyBatisConfig;
import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.dto.MarketingTemplateQuery;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Mapper XML、租户插件和事务验证新类型保存、筛选及读取后的消息组装。 */
class MarketingImageLinkMapperH2Test {
    private MarketingTemplateMapper mapper;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:marketing_image_link;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE marketing_template (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                    template_name VARCHAR(128) NOT NULL, link_mode TINYINT NOT NULL DEFAULT 1,
                    text_type VARCHAR(64), image_file_id BIGINT, content TEXT, body_text TEXT,
                    buttons JSON, promotion_link VARCHAR(512), mention_all TINYINT DEFAULT 0,
                    remark VARCHAR(255), created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
                    created_by BIGINT, deleted_at BIGINT)
                """);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MyBatisConfig productionConfig = new MyBatisConfig();
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setPlugins(productionConfig.mybatisPlusInterceptor(productionConfig.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/marketing/MarketingTemplateMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(MarketingTemplateMapper.class);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TenantContext.set(7L);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void storedCardRetainsImageAndDestinationAndIsIsolatedByTenant() {
        MarketingTemplate card = template(LinkMode.IMAGE_LINK);
        transaction.executeWithoutResult(status -> {
            mapper.insert(card);
            mapper.insert(template(LinkMode.IMAGE_TEXT));
        });
        TenantContext.set(8L);
        mapper.insert(template(LinkMode.IMAGE_LINK));
        assertThat(mapper.selectById(card.getId())).isNull();

        TenantContext.set(7L);
        MarketingTemplateQuery query = new MarketingTemplateQuery();
        query.setLinkMode(LinkMode.IMAGE_LINK.code());
        assertThat(mapper.countPage(query)).isEqualTo(1);
        assertThat(mapper.selectPage(query)).extracting(MarketingTemplate::getId).containsExactly(card.getId());
        MarketingTemplate stored = mapper.selectById(card.getId());
        assertThat(stored.getImageFileId()).isEqualTo(91L);
        assertThat(stored.getLinkMode()).isEqualTo(LinkMode.IMAGE_LINK.code());
        MarketingTemplateFile image = new MarketingTemplateFile();
        image.setContent(new byte[] {1, 2, 3});
        image.setContentType("image/png");
        var payload = new MarketingMessageComposer().compose(stored, image);
        assertThat(payload.messageType()).isEqualTo("LINK_CARD");
        assertThat(payload.linkCard().url()).isEqualTo("https://example.com/card");
        assertThat(payload.linkCard().title()).isEqualTo("卡片标题");
    }

    @Test
    void editingImageCardPersistsTypeAndRollbackRetainsPreviousDestination() {
        MarketingTemplate card = template(LinkMode.IMAGE_TEXT);
        mapper.insert(card);
        card.setLinkMode(LinkMode.IMAGE_LINK.code());
        transaction.executeWithoutResult(status -> assertThat(mapper.updateById(card)).isEqualTo(1));
        assertThat(mapper.selectById(card.getId()).getLinkMode()).isEqualTo(LinkMode.IMAGE_LINK.code());
        transaction.executeWithoutResult(status -> {
            card.setPromotionLink("https://example.com/rollback");
            mapper.updateById(card);
            status.setRollbackOnly();
        });
        assertThat(mapper.selectById(card.getId()).getPromotionLink()).isEqualTo("https://example.com/card");
    }

    private MarketingTemplate template(LinkMode mode) {
        MarketingTemplate template = new MarketingTemplate();
        template.setTemplateName(mode.name());
        template.setLinkMode(mode.code());
        template.setImageFileId(91L);
        template.setContent("卡片标题");
        template.setBodyText("卡片说明");
        template.setPromotionLink("https://example.com/card");
        template.setCreatedAt(1L);
        template.setUpdatedAt(1L);
        return template;
    }
}
