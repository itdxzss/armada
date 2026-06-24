package com.armada.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.shared.util.LineImporter.Persisted;
import com.armada.shared.util.LineImporter.Result;
import org.junit.jupiter.api.Test;

/**
 * 逐行导入骨架单测：验证跳空行、解析失败记错（带行号）、批内去重、DB 去重、统计聚合。
 */
class LineImporterTest {

    @Test
    void countsInsertedSkippedFailedWithLineNumberedErrors() {
        // 行: 1=a(插) 2=空(跳过不计) 3=" b "(trim 后 b,插) 4=bad(解析失败) 5=a(批内重复→跳) 6=dup_x(DB去重→跳)
        String text = "a\n\n b \nbad\na\ndup_x";

        Result r = LineImporter.run(
                text,
                line -> {
                    if (line.equals("bad")) {
                        throw new ImportLineException("坏行");
                    }
                    return line;
                },
                key -> key,
                value -> value.startsWith("dup_") ? Persisted.SKIPPED : Persisted.INSERTED);

        assertThat(r.total()).isEqualTo(5);
        assertThat(r.inserted()).isEqualTo(2);
        assertThat(r.skipped()).isEqualTo(2);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.errors()).hasSize(1);
        assertThat(r.errors().get(0)).contains("第 4 行").contains("坏行");
    }

    @Test
    void nullOrBlankTextYieldsEmptyResult() {
        Result rNull = LineImporter.run(null, l -> l, k -> k, v -> Persisted.INSERTED);
        assertThat(rNull.total()).isZero();
        assertThat(rNull.errors()).isEmpty();

        Result rBlank = LineImporter.run("\n  \n", l -> l, k -> k, v -> Persisted.INSERTED);
        assertThat(rBlank.total()).isZero();
        assertThat(rBlank.inserted()).isZero();
    }

    @Test
    void inBatchDedupSkipsRepeatedKeysBeforePersist() {
        // 同一 key 出现 3 次：仅首次落库，其余批内跳过（persist 只被调 1 次）。
        int[] persistCalls = {0};
        Result r = LineImporter.run(
                "x\nx\nx",
                l -> l,
                k -> k,
                v -> {
                    persistCalls[0]++;
                    return Persisted.INSERTED;
                });

        assertThat(r.total()).isEqualTo(3);
        assertThat(r.inserted()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(2);
        assertThat(persistCalls[0]).isEqualTo(1);
    }
}
