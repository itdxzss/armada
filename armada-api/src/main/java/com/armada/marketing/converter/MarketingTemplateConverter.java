package com.armada.marketing.converter;

import com.armada.marketing.model.MessageButton;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.vo.MarketingTemplateVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * 营销模板 entity ↔ DTO/VO 转换(MapStruct,编译期生成)。
 *
 * <p>{@code buttons} 列是 JSON 字符串,与 {@code List<MessageButton>} 的互转由本类的
 * 默认方法承担,MapStruct 按类型自动选用。</p>
 */
@Mapper(componentModel = "spring")
public interface MarketingTemplateConverter {

    /** 用于 buttons JSON 序列化/反序列化。 */
    ObjectMapper BUTTONS_JSON = new ObjectMapper();

    /** 写入参 → 实体(buttons List → JSON 字符串)。 */
    MarketingTemplate toEntity(MarketingTemplateDTO dto);

    /** 实体 → 出参(buttons JSON 字符串 → List)。 */
    MarketingTemplateVO toVO(MarketingTemplate entity);

    List<MarketingTemplateVO> toVOList(List<MarketingTemplate> entities);

    /** List<MessageButton> → JSON 字符串(供 toEntity 的 buttons 字段)。 */
    default String buttonsToJson(List<MessageButton> buttons) {
        if (buttons == null || buttons.isEmpty()) {
            return null;
        }
        try {
            return BUTTONS_JSON.writeValueAsString(buttons);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "消息按钮序列化失败");
        }
    }

    /** JSON 字符串 → List<MessageButton>(供 toVO 的 buttons 字段);坏数据返回空列表不抛。 */
    default List<MessageButton> buttonsFromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return BUTTONS_JSON.readValue(json, new TypeReference<List<MessageButton>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
