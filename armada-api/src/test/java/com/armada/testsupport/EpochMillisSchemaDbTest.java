package com.armada.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 验证业务时间列统一存 BIGINT epoch 毫秒,不再存 DATETIME/TIMESTAMP。 */
class EpochMillisSchemaDbTest extends DbTestBase {

    @Autowired
    private DataSource dataSource;

    @Test
    void legacyBusinessTimeColumnsAreBigintEpochMillis() throws Exception {
        List<ColumnRef> columns = List.of(
                new ColumnRef("marketing_template", "created_at"),
                new ColumnRef("marketing_template", "updated_at"),
                new ColumnRef("marketing_template", "deleted_at"),
                new ColumnRef("ip_proxy", "created_at"),
                new ColumnRef("ip_proxy", "updated_at"),
                new ColumnRef("ip_proxy", "deleted_at"),
                new ColumnRef("group_link_label", "created_at"),
                new ColumnRef("group_link_label", "updated_at"),
                new ColumnRef("group_link_label", "deleted_at"),
                new ColumnRef("group_link", "created_at"),
                new ColumnRef("group_link", "updated_at"),
                new ColumnRef("group_link", "deleted_at"),
                new ColumnRef("group_link_import_batch", "created_at"),
                new ColumnRef("group_link_import_batch", "deleted_at"),
                new ColumnRef("group_link_import_detail", "created_at"),
                new ColumnRef("tenant", "created_at"),
                new ColumnRef("tenant", "updated_at")
        );

        try (var c = dataSource.getConnection()) {
            String schema = c.getCatalog();
            for (ColumnRef ref : columns) {
                try (var rs = c.getMetaData().getColumns(schema, null, ref.table(), ref.column())) {
                    assertThat(rs.next()).as("%s.%s should exist", ref.table(), ref.column()).isTrue();
                    assertThat(rs.getInt("DATA_TYPE"))
                            .as("%s.%s jdbc type", ref.table(), ref.column())
                            .isEqualTo(Types.BIGINT);
                    assertThat(rs.getString("TYPE_NAME").toUpperCase())
                            .as("%s.%s sql type", ref.table(), ref.column())
                            .contains("BIGINT");
                }
            }
        }
    }

    private record ColumnRef(String table, String column) {
    }
}
