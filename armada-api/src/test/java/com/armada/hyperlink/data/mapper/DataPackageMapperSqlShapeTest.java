package com.armada.hyperlink.data.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 固化列表分页只 JOIN 统计读模型、不聚合号码表的 SQL 形状。 */
class DataPackageMapperSqlShapeTest {

    private static final Path XML = Path.of(
            "src/main/resources/mapper/hyperlink/data/DataPackageMapper.xml");

    @Test
    void selectPageDoesNotJoinOrGroupThePhoneTable() throws Exception {
        String xml = Files.readString(XML);
        Matcher matcher = Pattern.compile(
                "<select id=\"selectPage\"[\\s\\S]*?</select>").matcher(xml);

        assertThat(matcher.find()).isTrue();
        String selectPage = matcher.group().toLowerCase();
        assertThat(selectPage).contains("left join data_package_stat");
        assertThat(selectPage).doesNotContain(
                "join data_package_phone",
                "group by");
    }
}
