package com.armada.task.service;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.util.ImportLineException;
import com.armada.shared.util.LineImporter;
import com.armada.shared.util.LineImporter.Kind;
import com.armada.shared.util.LineImporter.LineOutcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 普通群链接任务的 TXT 料子解析器。
 *
 * <p>与 {@code HistoricalGroupMaterialParser} 的合同不同，故独立实现：本类把末尾
 * {@code A/a} 解释为"需设群管理员"而非营销账号，保留首次出现顺序而不重排，
 * 并返回逐行错误明细而不只是聚合统计。</p>
 *
 * <p>本类只吃字符串、不读文件流、不碰数据库，物理行号由 {@link LineImporter} 保真。</p>
 */
@Component
public class PullTaskMaterialTxtParser {

    /** 归一化号码允许的最短位数。 */
    private static final int PHONE_MIN_LENGTH = 7;

    /** 归一化号码允许的最长位数。 */
    private static final int PHONE_MAX_LENGTH = 15;

    /** 单个 TXT 允许的最大物理行数。 */
    public static final int MAX_LINE_COUNT = 20000;

    /** 号码中允许出现并在归一化时移除的展示字符：加号、空白、圆括号、短横线。 */
    private static final Pattern DISPLAY_CHARS = Pattern.compile("[+\\s()\\-]");

    /** 行分隔符，与 {@link LineImporter} 保持一致。 */
    private static final Pattern LINE_SEPARATOR = Pattern.compile("\\R");

    /**
     * 解析单个 TXT 的文本内容。
     *
     * @param fileName TXT 原始文件名，用于错误定位
     * @param content  TXT 全文；null 或空串返回空结果
     * @return 去重后的号码清单、逐行错误明细与统计
     * @throws BusinessException 物理行数超过 {@link #MAX_LINE_COUNT} 时
     */
    public ParseResult parse(String fileName, String content) {
        int totalLineCount = countPhysicalLines(content);
        if (totalLineCount > MAX_LINE_COUNT) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "文件 " + fileName + " 行数超过 " + MAX_LINE_COUNT + " 行，请拆分后重新上传");
        }

        Map<String, MutableMember> unique = new LinkedHashMap<>();
        List<LineError> errors = new ArrayList<>();
        int duplicateLineCount = 0;

        List<LineOutcome<ParsedLine, Void>> outcomes = LineImporter.run(
                content, PullTaskMaterialTxtParser::parseLine, ParsedLine::phone, record -> null);
        for (LineOutcome<ParsedLine, Void> outcome : outcomes) {
            if (outcome.kind() == Kind.FAILED) {
                errors.add(new LineError(outcome.lineNo(), outcome.reason()));
                continue;
            }
            if (outcome.kind() == Kind.DUPLICATE) {
                duplicateLineCount++;
                // 同号任一重复行带 A/a 时，唯一记录整体提升为需设管理员。
                if (outcome.record().adminRequired()) {
                    unique.get(outcome.record().phone()).promoteToAdmin();
                }
                continue;
            }
            unique.put(outcome.record().phone(),
                    new MutableMember(outcome.lineNo(), outcome.record()));
        }

        return new ParseResult(fileName, totalLineCount, errors.size(), duplicateLineCount,
                toMembers(unique), List.copyOf(errors));
    }

    /**
     * 统计物理行数；末尾换行不额外计一行。
     *
     * @param content TXT 全文
     * @return 物理行数
     */
    private static int countPhysicalLines(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        String[] lines = LINE_SEPARATOR.split(content, -1);
        int count = lines.length;
        if (count > 0 && lines[count - 1].isEmpty()) {
            count--;
        }
        return count;
    }

    /**
     * 按固定顺序清洗一行：剥离管理员标识 → 拒绝 JID → 移除展示字符 → 校验位数。
     *
     * @param line 已 trim 且非空的行原文
     * @return 清洗后的号码与管理员标识
     * @throws ImportLineException 行不合格时，消息即前端展示的失败原因
     */
    private static ParsedLine parseLine(String line) {
        boolean adminRequired = endsWithAdminMarker(line);
        String phoneToken = adminRequired ? line.substring(0, line.length() - 1).trim() : line;
        if (phoneToken.indexOf('@') >= 0) {
            throw new ImportLineException("不支持完整用户 JID，请只填手机号");
        }
        String phone = DISPLAY_CHARS.matcher(phoneToken).replaceAll("");
        if (phone.isEmpty() || !phone.chars().allMatch(Character::isDigit)) {
            throw new ImportLineException("号码含非法字符");
        }
        if (phone.length() < PHONE_MIN_LENGTH || phone.length() > PHONE_MAX_LENGTH) {
            throw new ImportLineException(
                    "号码必须是 " + PHONE_MIN_LENGTH + "-" + PHONE_MAX_LENGTH + " 位纯数字并包含国家码");
        }
        return new ParsedLine(phone, adminRequired);
    }

    /**
     * 判断行尾是否是管理员标识。
     *
     * @param line 行原文
     * @return 末尾为 {@code A} 或 {@code a} 时为真
     */
    private static boolean endsWithAdminMarker(String line) {
        char last = line.charAt(line.length() - 1);
        return last == 'A' || last == 'a';
    }

    /**
     * 按插入顺序编号并冻结为不可变清单。
     *
     * @param unique 首次出现顺序的唯一号码
     * @return 带连续 memberSeq 的成员清单
     */
    private static List<ParsedMember> toMembers(Map<String, MutableMember> unique) {
        List<ParsedMember> members = new ArrayList<>(unique.size());
        int seq = 1;
        for (MutableMember value : unique.values()) {
            members.add(value.toParsedMember(seq++));
        }
        return List.copyOf(members);
    }

    /** 清洗后的单行结果。 */
    private record ParsedLine(String phone, boolean adminRequired) {
    }

    /** 去重后的唯一号码，管理员标识可被后续重复行提升。 */
    private static final class MutableMember {

        private final int sourceLineNo;
        private final String phone;
        private boolean adminRequired;

        private MutableMember(int sourceLineNo, ParsedLine parsed) {
            this.sourceLineNo = sourceLineNo;
            this.phone = parsed.phone();
            this.adminRequired = parsed.adminRequired();
        }

        private void promoteToAdmin() {
            this.adminRequired = true;
        }

        private ParsedMember toParsedMember(int memberSeq) {
            return new ParsedMember(memberSeq, sourceLineNo, phone, adminRequired);
        }
    }

    /**
     * 去重后的料子成员。
     *
     * @param memberSeq       文件内去重后稳定顺序，从 1 起
     * @param sourceLineNo    首次有效出现的原始物理行号
     * @param normalizedPhone 归一化后的纯数字号码
     * @param adminRequired   是否需要在入群后设为群管理员
     */
    public record ParsedMember(int memberSeq, int sourceLineNo, String normalizedPhone,
                               boolean adminRequired) {
    }

    /**
     * 单行失败明细。
     *
     * @param lineNo 原始物理行号
     * @param reason 失败原因，直接展示给运营
     */
    public record LineError(int lineNo, String reason) {
    }

    /**
     * 单个 TXT 的解析结果。
     *
     * @param fileName           原始文件名
     * @param totalLineCount     物理行数
     * @param invalidLineCount   非法行数
     * @param duplicateLineCount 文件内重复号码行数
     * @param members            去重后的号码清单，保留首次出现顺序
     * @param errors             逐行失败明细
     */
    public record ParseResult(String fileName, int totalLineCount, int invalidLineCount,
                              int duplicateLineCount, List<ParsedMember> members,
                              List<LineError> errors) {

        /** 是否有至少一个有效号码；零有效号码的文件不得进入匹配池。 */
        public boolean hasValidMember() {
            return !members.isEmpty();
        }
    }
}
