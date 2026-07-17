package com.armada.group.service;

import com.armada.group.model.enums.HistoricalGroupMaterialType;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.util.WhatsappJids;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 历史群拉人料子清洗器。
 *
 * <p>文件读取统一复用 {@link FileLinesExtractor}；有效手机号按首次出现保留稳定行号，
 * 末尾 {@code A/a} 表示营销账号，同号普通行和营销行并存时只保留营销身份。</p>
 */
@Component
public class HistoricalGroupMaterialParser {

    private final FileLinesExtractor linesExtractor;

    /**
     * 创建料子清洗器。
     *
     * @param linesExtractor TXT、CSV 和 Excel 统一行提取器
     */
    public HistoricalGroupMaterialParser(FileLinesExtractor linesExtractor) {
        this.linesExtractor = linesExtractor;
    }

    /**
     * 解析上传文件并按“营销在前、普通在后”返回唯一号码。
     *
     * @param file TXT、CSV、XLSX 或 XLS 文件
     * @return 清洗成员及普通、营销、无效、重复统计
     */
    public ParseResult parse(MultipartFile file) {
        List<String> lines = linesExtractor.extract(file, null);
        Map<String, MutableMember> unique = new LinkedHashMap<>();
        int invalidCount = 0;
        int duplicateCount = 0;
        for (int index = 0; index < lines.size(); index++) {
            ParsedLine parsed = parseLine(lines.get(index));
            if (parsed == null) {
                invalidCount++;
                continue;
            }
            MutableMember existing = unique.get(parsed.phone());
            if (existing == null) {
                unique.put(parsed.phone(), new MutableMember(
                        parsed.phone(), parsed.materialType(), index + 1));
                continue;
            }
            duplicateCount++;
            if (parsed.materialType() == HistoricalGroupMaterialType.MARKETING) {
                existing.promoteToMarketing();
            }
        }
        return result(unique, invalidCount, duplicateCount);
    }

    private static ParsedLine parseLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String token = line.split("[,，;；\\t]+", 2)[0].trim();
        boolean marketing = endsWithMarketingMarker(token);
        String phoneToken = marketing ? token.substring(0, token.length() - 1).trim() : token;
        String phone = normalizePhone(phoneToken);
        if (phone == null) {
            return null;
        }
        return new ParsedLine(
                phone,
                marketing ? HistoricalGroupMaterialType.MARKETING : HistoricalGroupMaterialType.NORMAL);
    }

    private static boolean endsWithMarketingMarker(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char marker = value.charAt(value.length() - 1);
        return marker == 'A' || marker == 'a';
    }

    private static String normalizePhone(String value) {
        try {
            String jid = WhatsappJids.userJid(value);
            int separator = jid.indexOf('@');
            String phone = separator > 0 ? jid.substring(0, separator) : jid;
            if (phone.length() < 7 || phone.length() > 15
                    || !phone.chars().allMatch(Character::isDigit)) {
                return null;
            }
            return phone;
        } catch (ProtocolException ex) {
            return null;
        }
    }

    private static ParseResult result(
            Map<String, MutableMember> unique,
            int invalidCount,
            int duplicateCount) {
        List<ParsedMember> marketing = new ArrayList<>();
        List<ParsedMember> normal = new ArrayList<>();
        for (MutableMember value : unique.values()) {
            ParsedMember member = value.toParsedMember();
            if (member.materialType() == HistoricalGroupMaterialType.MARKETING) {
                marketing.add(member);
            } else {
                normal.add(member);
            }
        }
        List<ParsedMember> ordered = new ArrayList<>(unique.size());
        ordered.addAll(marketing);
        ordered.addAll(normal);
        return new ParseResult(
                List.copyOf(ordered), normal.size(), marketing.size(), invalidCount, duplicateCount);
    }

    /**
     * 清洗后的单个成员。
     *
     * @param phone        7 至 15 位纯数字号码
     * @param materialType 普通或营销身份
     * @param lineNo       提取后首次有效出现的一基行号
     */
    public record ParsedMember(String phone, HistoricalGroupMaterialType materialType, int lineNo) {
    }

    /**
     * 料子清洗结果。
     *
     * @param members        营销优先且类型内稳定排序的唯一成员
     * @param normalCount    普通成员数
     * @param marketingCount 营销成员数
     * @param invalidCount   无法清洗为合法手机号的行数
     * @param duplicateCount 重复有效手机号的后续行数
     */
    public record ParseResult(
            List<ParsedMember> members,
            int normalCount,
            int marketingCount,
            int invalidCount,
            int duplicateCount) {
    }

    private record ParsedLine(String phone, HistoricalGroupMaterialType materialType) {
    }

    private static final class MutableMember {
        private final String phone;
        private HistoricalGroupMaterialType materialType;
        private final int lineNo;

        private MutableMember(String phone, HistoricalGroupMaterialType materialType, int lineNo) {
            this.phone = phone;
            this.materialType = materialType;
            this.lineNo = lineNo;
        }

        private void promoteToMarketing() {
            materialType = HistoricalGroupMaterialType.MARKETING;
        }

        private ParsedMember toParsedMember() {
            return new ParsedMember(phone, materialType, lineNo);
        }
    }
}
