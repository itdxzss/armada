package com.armada.marketing.grouppull.service;

import com.armada.group.service.FileLinesExtractor;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** 解析拉群营销任务唯一上传的 TXT/CSV 料子文件。 */
@Component
public class GroupPullMarketingMaterialParser {

    private static final int MIN_PHONE_LENGTH = 7;
    private static final int MAX_PHONE_LENGTH = 15;

    private final FileLinesExtractor fileLinesExtractor;

    public GroupPullMarketingMaterialParser(FileLinesExtractor fileLinesExtractor) {
        this.fileLinesExtractor = fileLinesExtractor;
    }

    /**
     * 清洗、去重并保留首次出现顺序。
     *
     * @param file 本次任务的唯一料子文件
     * @return 连续编号的有效手机号
     */
    public List<ParsedMaterial> parse(MultipartFile file) {
        validateFile(file);
        Set<String> phones = new LinkedHashSet<>();
        for (String line : fileLinesExtractor.extract(file, null)) {
            normalizePhone(line).ifPresent(phones::add);
        }
        if (phones.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "料子文件中没有有效手机号");
        }
        List<ParsedMaterial> materials = new ArrayList<>(phones.size());
        int lineNo = 1;
        for (String phone : phones) {
            materials.add(new ParsedMaterial(lineNo++, phone));
        }
        return List.copyOf(materials);
    }

    private static void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "料子文件不能为空");
        }
        String filename = file.getOriginalFilename();
        String normalized = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (!normalized.endsWith(".txt") && !normalized.endsWith(".csv")) {
            throw new BusinessException(ErrorCode.VALIDATION, "料子文件仅支持 TXT、CSV 格式");
        }
    }

    private static java.util.Optional<String> normalizePhone(String line) {
        if (line == null || line.isBlank() || line.contains("@")) {
            return java.util.Optional.empty();
        }
        try {
            String jid = WhatsappJids.userJid(line);
            String phone = jid.substring(0, jid.indexOf('@'));
            if (phone.length() < MIN_PHONE_LENGTH || phone.length() > MAX_PHONE_LENGTH
                    || !phone.chars().allMatch(Character::isDigit)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(phone);
        } catch (ProtocolException | IndexOutOfBoundsException ex) {
            return java.util.Optional.empty();
        }
    }

    /** 有效料子在去重结果中的稳定顺序。 */
    public record ParsedMaterial(int lineNo, String phone) {
    }
}
