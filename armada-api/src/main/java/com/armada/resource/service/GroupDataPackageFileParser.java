package com.armada.resource.service;

import com.armada.task.service.PullTaskMaterialTxtParser;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 用既有拉群纯解析规则分段解析大文件，跨段保留首次顺序和管理员标记。 */
@Component
public class GroupDataPackageFileParser {
    /** 已确认的单次有效号码上限。 */
    public static final int MAX_PHONES = 100_000;
    private final PullTaskMaterialTxtParser parser;
    /** 复用标准拉群解析服务，不引用任务实体。 */
    public GroupDataPackageFileParser(PullTaskMaterialTxtParser parser) { this.parser = parser; }

    /** 严格UTF8解析，数量超限时整份拒绝。 */
    public ParsedFile parse(String fileName, byte[] bytes) {
        String content = decode(bytes);
        if (content.startsWith("\uFEFF")) { content = content.substring(1); }
        String[] lines = content.split("\\R", -1);
        int lineCount = lines.length;
        if (lineCount > 0 && lines[lineCount - 1].isEmpty()) { lineCount--; }
        Map<String, ParsedPhone> unique = new LinkedHashMap<>();
        int invalid = 0;
        int duplicates = 0;
        for (int offset = 0; offset < lineCount; offset += PullTaskMaterialTxtParser.MAX_LINE_COUNT) {
            int end = Math.min(offset + PullTaskMaterialTxtParser.MAX_LINE_COUNT, lineCount);
            var parsed = parser.parse(fileName, String.join("\n", Arrays.copyOfRange(lines, offset, end)));
            invalid += parsed.invalidLineCount(); duplicates += parsed.duplicateLineCount();
            for (var member : parsed.members()) {
                ParsedPhone previous = unique.get(member.normalizedPhone());
                if (previous == null) {
                    unique.put(member.normalizedPhone(), new ParsedPhone(member.normalizedPhone(),
                            member.adminRequired(), offset + member.sourceLineNo()));
                } else {
                    duplicates++;
                    unique.put(previous.phone(), new ParsedPhone(previous.phone(),
                            previous.adminRequired() || member.adminRequired(), previous.sourceLineNo()));
                }
            }
            if (unique.size() > MAX_PHONES) { throw invalid("单次最多导入100000个有效号码"); }
        }
        return new ParsedFile(lineCount, invalid, duplicates, new ArrayList<>(unique.values()));
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) { throw invalid("号码文件必须是UTF-8 TXT"); }
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION, message); }
    /** 文件内去重后的首行及管理员属性。 */
    public record ParsedPhone(String phone, boolean adminRequired, int sourceLineNo) { }
    /** 物理行统计与有序有效成员。 */
    public record ParsedFile(int totalRows, int invalidRows, int duplicatedRows, List<ParsedPhone> phones) { }
}
