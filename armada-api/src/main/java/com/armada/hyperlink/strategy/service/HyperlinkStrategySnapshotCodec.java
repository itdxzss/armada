package com.armada.hyperlink.strategy.service;

import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.service.HyperlinkAccountFilterNormalizer;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** 策略账号筛选使用任务同一归一化器的 JSON 快照编解码入口。 */
@Component
public class HyperlinkStrategySnapshotCodec {

    private final ObjectMapper objectMapper;
    private final HyperlinkAccountFilterNormalizer normalizer;

    public HyperlinkStrategySnapshotCodec(
            ObjectMapper objectMapper,
            HyperlinkAccountFilterNormalizer normalizer) {
        this.objectMapper = objectMapper;
        this.normalizer = normalizer;
    }

    /** 校验、归一化并编码前端提交的账号筛选合同。 */
    public Encoded encode(HyperlinkAccountFilterDTO input) {
        HyperlinkAccountFilterDTO normalized = normalizer.normalize(input);
        try {
            return new Encoded(normalized, objectMapper.writeValueAsString(normalized));
        } catch (JsonProcessingException exception) {
            throw validation("策略账号筛选无法序列化");
        }
    }

    /** 解码数据库快照并再次执行同一白名单与版本校验。 */
    public HyperlinkAccountFilterDTO decode(String json) {
        try {
            return normalizer.normalize(objectMapper.readValue(json, HyperlinkAccountFilterDTO.class));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw validation("策略账号筛选快照无法解析");
        }
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    /** 同一次归一化产生的结构化筛选与持久化 JSON。 */
    public record Encoded(HyperlinkAccountFilterDTO value, String json) {
    }
}
