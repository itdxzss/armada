package com.armada.shared.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 逐行文本导入通用骨架：按行 trim 跳空 → 解析(抛 {@link ImportLineException}=失败行)→ 批内去重 → 落库，
 * 产出每行的结构化结果(行号/原文/类别/原因/记录/落库返回),由调用方据此汇总统计或落明细。本类不碰 DB、不持状态。
 */
public final class LineImporter {

    private LineImporter() {}

    /** 单行类别。 */
    public enum Kind {
        /** 解析失败(格式不合格)。 */
        FAILED,
        /** 批内重复(同去重键本批已出现)。 */
        DUPLICATE,
        /** 已交付 persist(落库结果见 {@code persistResult})。 */
        PERSISTED
    }

    /**
     * 单行产出。
     *
     * @param lineNo        物理行号(从 1 起)
     * @param raw           trim 后原文
     * @param kind          类别
     * @param reason        失败原因(仅 FAILED 非空)
     * @param record        解析后的记录(FAILED 为 null)
     * @param persistResult persist 返回值(仅 PERSISTED 非空)
     * @param <T> 记录类型
     * @param <R> 落库返回类型
     */
    public record LineOutcome<T, R>(int lineNo, String raw, Kind kind, String reason, T record, R persistResult) {}

    /** 行解析器:行原文 → 记录;不合格抛 {@link ImportLineException}(带原因)。 */
    @FunctionalInterface
    public interface LineParser<T> { T parse(String line); }

    /**
     * 跑一次逐行导入,返回每行产出。
     *
     * @param text     多行原文(null/空 → 空列表)
     * @param parser   行解析器
     * @param dedupKey 记录 → 批内去重键
     * @param persist  记录 → 落库结果(实现内做 DB 去重/收编/插入)
     * @param <T> 记录类型
     * @param <R> 落库返回类型
     * @return 每行产出列表(空行不计入)
     */
    public static <T, R> List<LineOutcome<T, R>> run(String text,
            LineParser<T> parser, Function<T, Object> dedupKey, Function<T, R> persist) {
        List<LineOutcome<T, R>> out = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        String[] lines = text == null ? new String[0] : text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].trim();
            if (raw.isEmpty()) { continue; }
            int lineNo = i + 1;
            T record;
            try {
                record = parser.parse(raw);
            } catch (ImportLineException e) {
                out.add(new LineOutcome<>(lineNo, raw, Kind.FAILED, e.getMessage(), null, null));
                continue;
            }
            if (!seen.add(dedupKey.apply(record))) {
                out.add(new LineOutcome<>(lineNo, raw, Kind.DUPLICATE, null, record, null));
                continue;
            }
            out.add(new LineOutcome<>(lineNo, raw, Kind.PERSISTED, null, record, persist.apply(record)));
        }
        return out;
    }
}
