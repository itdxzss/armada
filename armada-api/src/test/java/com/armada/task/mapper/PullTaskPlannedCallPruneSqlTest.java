package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** H2 不模拟 MySQL 单表 UPDATE 的从左到右赋值，额外锁定空批判断必须先读取原计数。 */
class PullTaskPlannedCallPruneSqlTest {

    @Test
    void mysqlEmptyCallChecksReadOriginalCountsBeforeEitherCountIsDecremented() throws Exception {
        String xml = Files.readString(Path.of(
                "src/main/resources/mapper/task/PullTaskPullCallMapper.xml"));
        int start = xml.indexOf("<update id=\"prunePlannedParticipant\">");
        String sql = xml.substring(start, xml.indexOf("</update>", start));
        int lastEmptyCheck = sql.lastIndexOf("WHEN planned_material_count + planned_station_count = 1");

        assertThat(lastEmptyCheck).isGreaterThanOrEqualTo(0);
        assertThat(sql.indexOf("planned_material_count = planned_material_count"))
                .as("MySQL must evaluate every empty-call decision before changing material count")
                .isGreaterThan(lastEmptyCheck);
        assertThat(sql.indexOf("planned_station_count = planned_station_count"))
                .as("MySQL must evaluate every empty-call decision before changing station count")
                .isGreaterThan(lastEmptyCheck);
    }
}
