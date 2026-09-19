package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 真实生产 Mapper 和租户插件在 H2 MySQL 模式下的创建者写入回归。 */
@SpringJUnitConfig(GroupCreatorCompatibilityMapperH2Test.TestConfig.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class GroupCreatorCompatibilityMapperH2Test {
    @Autowired private DataSource dataSource;
    @Autowired private GroupLinkPreviewMapper mapper;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE group_link_preview (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL, owner_phone VARCHAR(32),
                  creator_phone_source TINYINT NOT NULL DEFAULT 2, creator_country_iso2 VARCHAR(2), creator_continent_code VARCHAR(32),
                  last_preview_at BIGINT, metadata_observed_at BIGINT,
                  created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
                  UNIQUE(tenant_id, group_link_id))
                """);
        TenantContext.set(7L);
    }

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void derivedEvidenceNeverDowngradesConfirmedPhoneEvenWhenTheNumberMatches() {
        mapper.upsertCreatorCompatibility(List.of(row(10L, "916375552817", "IN", 100L)));
        Map<String, Object> confirmed = stored(7L, 10L);
        for (String phone : List.of("916375552817", "2348083697499")) {
            GroupLinkPreview derived = row(10L, phone, "NG", 300L);
            derived.setCreatorPhoneSource(1);
            mapper.upsertCreatorCompatibility(List.of(derived));
            assertThat(stored(7L, 10L)).isEqualTo(confirmed);
        }
    }

    @Test
    void changedConfirmedPhoneClearsDerivedGeographyWhenLookupFails() {
        GroupLinkPreview derived = row(10L, "916375552817", "IN", 300L);
        derived.setCreatorPhoneSource(1);
        mapper.upsertCreatorCompatibility(List.of(derived));
        GroupLinkPreview confirmed = row(10L, "2348083697499", null, 100L);
        confirmed.setCreatorCountryObserved(false);
        mapper.upsertCreatorCompatibility(List.of(confirmed));
        assertThat(stored(7L, 10L)).containsEntry("owner_phone", "2348083697499")
                .containsEntry("creator_phone_source", 2)
                .containsEntry("creator_country_iso2", null)
                .containsEntry("creator_continent_code", null)
                .containsEntry("metadata_observed_at", null);
        mapper.upsertCreatorCompatibility(List.of(row(10L, "2348083697499", "NG", 200L)));
        assertThat(stored(7L, 10L)).containsEntry("creator_country_iso2", "NG");
    }

    @Test
    void samePhoneUpgradesSourceAndMissingObservationDoesNotUndoIt() {
        GroupLinkPreview derived = row(10L, "916375552817", "IN", 300L);
        derived.setCreatorPhoneSource(1);
        mapper.upsertCreatorCompatibility(List.of(derived));
        mapper.upsertCreatorCompatibility(List.of(row(10L, "916375552817", null, 100L)));
        assertThat(stored(7L, 10L)).containsEntry("creator_phone_source", 2)
                .containsEntry("creator_country_iso2", "IN");
        Map<String, Object> confirmed = stored(7L, 10L);
        mapper.upsertCreatorCompatibility(List.of(row(10L, null, null, 900L), derived));
        assertThat(stored(7L, 10L)).isEqualTo(confirmed);
    }

    @Test
    void emptyLegacyRowAcceptsDerivedSourceAndTenantsRemainIndependent() {
        jdbc.update("INSERT INTO group_link_preview(tenant_id,group_link_id,owner_phone,creator_phone_source,created_at,updated_at) VALUES(7,10,' ',0,1,1)");
        GroupLinkPreview derived = row(10L, "916375552817", "IN", 100L);
        derived.setCreatorPhoneSource(1);
        mapper.upsertCreatorCompatibility(List.of(derived));
        TenantContext.set(8L);
        mapper.upsertCreatorCompatibility(List.of(row(10L, "2348083697499", "NG", 200L)));
        assertThat(stored(7L, 10L)).containsEntry("creator_phone_source", 1)
                .containsEntry("owner_phone", "916375552817");
        assertThat(stored(8L, 10L)).containsEntry("creator_phone_source", 2)
                .containsEntry("owner_phone", "2348083697499");
    }

    @Test
    void confirmedPhoneReplacesDerivedIdentityEvenWhenItsObservationIsOlder() {
        GroupLinkPreview inferred = row(10L, "916375552817", "IN", 200L);
        inferred.setCreatorPhoneSource(1);
        mapper.upsertCreatorCompatibility(List.of(inferred));
        mapper.upsertCreatorCompatibility(List.of(row(10L, "2348083697499", "NG", 100L)));
        assertThat(stored(7L, 10L)).containsEntry("owner_phone", "2348083697499")
                .containsEntry("creator_country_iso2", "NG").containsEntry("creator_phone_source", 2);
    }

    @Test
    void unknownAndDifferentPhoneCannotEraseOrAdvanceConfirmedIdentity() {
        mapper.upsertCreatorCompatibility(List.of(row(10L, "2348083697499", "NG", 100L)));
        Map<String, Object> original = stored(7L, 10L);
        mapper.upsertCreatorCompatibility(List.of(row(10L, null, null, 200L)));
        assertThat(stored(7L, 10L)).isEqualTo(original);
        mapper.upsertCreatorCompatibility(List.of(row(10L, "919000000001", "IN", 300L)));
        assertThat(stored(7L, 10L)).isEqualTo(original);
    }

    @Test
    void samePhoneEnrichesCountryButMissingOrStaleCountryDoesNotEraseIt() {
        mapper.upsertCreatorCompatibility(List.of(row(10L, "2348083697499", null, 100L)));
        mapper.upsertCreatorCompatibility(List.of(row(10L, "2348083697499", "NG", 200L)));
        mapper.upsertCreatorCompatibility(List.of(row(10L, "2348083697499", null, 300L)));
        mapper.upsertCreatorCompatibility(List.of(row(10L, "2348083697499", "IN", 150L)));
        assertThat(stored(7L, 10L)).containsEntry("owner_phone", "2348083697499")
                .containsEntry("creator_country_iso2", "NG").containsEntry("metadata_observed_at", 200L)
                .containsEntry("last_preview_at", 300L);
    }

    @Test
    void fillsLegacyBlankPhoneAndKeepsBatchRowsAndTenantsIndependent() {
        jdbc.update("INSERT INTO group_link_preview(tenant_id,group_link_id,owner_phone,created_at,updated_at) VALUES(7,10,' ',1,1)");
        mapper.upsertCreatorCompatibility(List.of(row(10L, "2348083697499", "NG", 100L),
                row(11L, "919000000001", "IN", 100L)));
        TenantContext.set(8L);
        mapper.upsertCreatorCompatibility(List.of(row(10L, "5218129230974", "MX", 200L)));
        assertThat(stored(7L, 10L)).containsEntry("owner_phone", "2348083697499");
        assertThat(stored(7L, 11L)).containsEntry("owner_phone", "919000000001");
        assertThat(stored(8L, 10L)).containsEntry("owner_phone", "5218129230974");
    }

    @Test
    void compatibilityWriteParticipatesInSpringTransactionRollback() {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.executeWithoutResult(status -> {
            mapper.upsertCreatorCompatibility(List.of(row(10L, "2348083697499", "NG", 100L)));
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_link_preview", Integer.class)).isZero();
    }

    private Map<String, Object> stored(long tenant, long group) {
        return jdbc.queryForMap("SELECT owner_phone, creator_phone_source, creator_country_iso2, creator_continent_code, "
                + "last_preview_at, metadata_observed_at, updated_at FROM group_link_preview "
                + "WHERE tenant_id=? AND group_link_id=?", tenant, group);
    }

    private static GroupLinkPreview row(long group, String phone, String country, long time) {
        GroupLinkPreview row = new GroupLinkPreview();
        row.setGroupLinkId(group);
        row.setOwnerPhone(phone);
        row.setOwnerPhoneObserved(true);
        row.setCreatorCountryIso2(country);
        row.setCreatorContinentCode(country == null ? null : "NG".equals(country) ? "AFRICA" : "ASIA");
        row.setCreatorCountryObserved(true);
        row.setLastPreviewAt(time);
        row.setMetadataObservedAt(time);
        row.setCreatedAt(time);
        row.setUpdatedAt(time);
        return row;
    }

    /** 测试加载生产 Mapper XML 与生产租户插件。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:group_creator_mapper_test;"
                    + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
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
            factory.setMapperLocations(new ClassPathResource(
                    "mapper/group/GroupLinkPreviewMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        GroupLinkPreviewMapper groupLinkPreviewMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupLinkPreviewMapper.class);
        }
    }
}
