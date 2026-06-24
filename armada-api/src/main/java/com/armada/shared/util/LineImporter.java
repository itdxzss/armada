package com.armada.shared.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 逐行文本导入通用骨架。沉淀各类导入（IP 代理 / 账号 / 群链接…）共有的逐行处理流程：
 *
 * <ol>
 *   <li>按换行分行，trim 后跳过空行；</li>
 *   <li>逐行带行号解析+校验：解析器抛 {@link ImportLineException} 即记失败行（行号+原因），不中断其它行；</li>
 *   <li>批内去重：按 {@code dedupKey} 在本次导入内去重，重复行计入跳过；</li>
 *   <li>落库：{@code persist} 返回 {@link Persisted#INSERTED}/{@link Persisted#SKIPPED}（库内已存在等）；</li>
 *   <li>聚合统计 total/inserted/skipped/failed + errors。</li>
 * </ol>
 *
 * <p>各导入只提供三段差异：行解析器（分隔符/字段校验）、去重键、落库（DB 去重 + 插入）。本类不碰 DB、不持状态。</p>
 */
public final class LineImporter {

    private LineImporter() {
    }

    /** 单行落库结果。 */
    public enum Persisted {
        /** 已新增。 */
        INSERTED,
        /** 已跳过（库内已存在等）。 */
        SKIPPED
    }

    /** 导入结果统计。 */
    public record Result(int total, int inserted, int skipped, int failed, List<String> errors) {
    }

    /** 行解析器：把一行原文解析+校验成记录 {@code T}；不合格抛 {@link ImportLineException}（带原因）。 */
    @FunctionalInterface
    public interface LineParser<T> {
        T parse(String line);
    }

    /**
     * 跑一次逐行导入。
     *
     * @param text     多行原文（null/空 → 空结果）
     * @param parser   行解析器：行 → 记录，不合格抛 {@link ImportLineException}
     * @param dedupKey 记录 → 批内去重键（同键视为重复）
     * @param persist  记录 → 落库结果（实现内做 DB 去重 + 插入）
     * @param <T>      行记录类型
     * @return 导入统计
     */
    public static <T> Result run(String text,
            LineParser<T> parser,
            Function<T, Object> dedupKey,
            Function<T, Persisted> persist) {
        int total = 0;
        int inserted = 0;
        int skipped = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        Set<Object> seen = new HashSet<>();

        String[] lines = text == null ? new String[0] : text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].trim();
            if (raw.isEmpty()) {
                continue;
            }
            total++;
            int lineNo = i + 1;
            T record;
            try {
                record = parser.parse(raw);
            } catch (ImportLineException e) {
                failed++;
                errors.add("第 " + lineNo + " 行：" + e.getMessage());
                continue;
            }
            if (!seen.add(dedupKey.apply(record))) {
                skipped++;
                continue;
            }
            if (persist.apply(record) == Persisted.INSERTED) {
                inserted++;
            } else {
                skipped++;
            }
        }
        return new Result(total, inserted, skipped, failed, errors);
    }
}
