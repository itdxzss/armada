package com.armada.shared.util;

import static org.junit.jupiter.api.Assertions.*;

import com.armada.shared.util.LineImporter.Kind;
import com.armada.shared.util.LineImporter.LineOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 逐行导入骨架单测：验证跳空行、解析失败记错（带行号）、批内去重、落库调用、结构化产出。
 */
class LineImporterTest {

    @Test
    void emitsPerLineOutcomes() {
        List<LineOutcome<String, Boolean>> out = LineImporter.run(
            "a\nbad\na\n\n c ",
            line -> { if (line.equals("bad")) throw new ImportLineException("坏行"); return line; },
            s -> s,                                  // dedupKey = 自身
            s -> Boolean.TRUE);                      // persist 恒 INSERTED
        assertEquals(4, out.size());                 // 空行跳过,4 个非空
        assertEquals(Kind.PERSISTED, out.get(0).kind());   // a
        assertEquals(Kind.FAILED, out.get(1).kind());      // bad
        assertEquals("坏行", out.get(1).reason());
        assertEquals(2, out.get(1).lineNo());              // 物理行号
        assertEquals(Kind.DUPLICATE, out.get(2).kind());   // 第二个 a
        assertEquals(Kind.PERSISTED, out.get(3).kind());   // c(trim)
    }

    @Test
    void nullTextYieldsEmpty() {
        assertTrue(LineImporter.run(null, s -> s, s -> s, s -> 1).isEmpty());
    }
}
