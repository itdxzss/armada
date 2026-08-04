package com.armada.task.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * 普通群链接执行域 Mapper 测试的 H2 基座。
 *
 * <p>用 H2 MySQL 模式加载真实 Mapper XML 和生产 {@code MyBatisConfig} 的租户拦截器，
 * 让 Mapper SQL、租户隔离、生成列和部分唯一索引在本地就能验证。</p>
 */
public final class PullTaskNormalLinkH2Support {

    private PullTaskNormalLinkH2Support() {
    }

    /**
     * 构造隔离的 H2 内存库。
     *
     * @param dbName 库名；每个测试类用不同的名字避免互相污染
     * @return H2 数据源
     */
    public static DataSource dataSource(String dbName) {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:" + dbName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        h2.setUser("sa");
        h2.setPassword("");
        return h2;
    }

    /**
     * 用生产租户拦截器和真实 Mapper XML 构造 SqlSessionFactory。
     *
     * @param dataSource H2 数据源
     * @param interceptor 生产 MyBatis-Plus 拦截器（含租户行隔离）
     * @param mapperXmlPaths classpath 下的 Mapper XML 路径
     * @return SqlSessionFactory
     * @throws Exception 构造失败时抛出
     */
    public static SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                               MybatisPlusInterceptor interceptor,
                                               String... mapperXmlPaths) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setUseGeneratedKeys(true);

        Resource[] resources = Arrays.stream(mapperXmlPaths)
                .map(ClassPathResource::new)
                .toArray(Resource[]::new);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setPlugins(interceptor);
        factoryBean.setMapperLocations(resources);
        return factoryBean.getObject();
    }

    /**
     * 清库、重建全部表，再执行调用方给的 fixture 语句。
     *
     * @param dataSource H2 数据源
     * @param extraStatements 建表后要执行的 fixture 语句
     * @throws SQLException 执行失败时抛出
     */
    public static void resetSchema(DataSource dataSource, String... extraStatements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            for (String ddl : PullTaskNormalLinkSchema.all()) {
                statement.execute(ddl);
            }
            for (String sql : extraStatements) {
                statement.execute(sql);
            }
        }
    }

    /** 重建普通拉群表，并为涉及命令取消的测试按需追加平台 Outbox 表。 */
    public static void resetSchemaWithProtocolOutbox(
            DataSource dataSource,
            String... extraStatements) throws SQLException {
        String[] statements = new String[extraStatements.length + 1];
        statements[0] = PullTaskNormalLinkSchema.protocolCommandOutbox();
        System.arraycopy(extraStatements, 0, statements, 1, extraStatements.length);
        resetSchema(dataSource, statements);
    }
}
