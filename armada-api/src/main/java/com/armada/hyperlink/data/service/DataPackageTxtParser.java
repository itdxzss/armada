package com.armada.hyperlink.data.service;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 数据包 UTF-8 TXT 解析器；不访问数据库，也不记录号码内容。 */
@Component
public class DataPackageTxtParser {

    /** 合同冻结的单次非空行数上限。 */
    public static final int MAX_NON_EMPTY_ROWS = 5_000;

    private static final Pattern PHONE_PATTERN = Pattern.compile("^[0-9]{6,20}$");

    /**
     * 完整解析 TXT，忽略空行并对合法号码按首次出现顺序去重。
     *
     * @param bytes 上传文件字节，必须是严格 UTF-8，可带 BOM
     * @return 去重号码与合同计数
     * @throws BusinessException 编码非法或非空行超过 5000 时抛出
     */
    public ParsedDataPackagePhones parse(byte[] bytes) {
        String content = decode(bytes);
        String[] lines = content.split("\\R", -1);
        Set<String> unique = new LinkedHashSet<>();
        int totalRows = 0;
        int invalidRows = 0;
        int duplicatedRows = 0;
        for (String line : lines) {
            String normalized = stripBom(line.trim());
            if (normalized.isEmpty()) {
                continue;
            }
            totalRows++;
            if (totalRows > MAX_NON_EMPTY_ROWS) {
                throw new BusinessException(
                        ErrorCode.VALIDATION,
                        "单次最多导入 5000 条非空号码");
            }
            if (!PHONE_PATTERN.matcher(normalized).matches()) {
                invalidRows++;
            } else if (!unique.add(normalized)) {
                duplicatedRows++;
            }
        }
        return new ParsedDataPackagePhones(
                List.copyOf(new ArrayList<>(unique)), totalRows, invalidRows, duplicatedRows);
    }

    private static String decode(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new BusinessException(ErrorCode.VALIDATION, "号码文件必须为 UTF-8 TXT");
        }
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1).trim() : value;
    }
}
