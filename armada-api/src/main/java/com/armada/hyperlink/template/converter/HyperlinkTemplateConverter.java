package com.armada.hyperlink.template.converter;

import com.armada.hyperlink.template.model.HyperlinkButton;
import com.armada.hyperlink.template.model.HyperlinkMessageContent;
import com.armada.hyperlink.template.model.dto.HyperlinkTemplateCreateDTO;
import com.armada.hyperlink.template.model.dto.HyperlinkTemplateUpdateDTO;
import com.armada.hyperlink.template.model.entity.HyperlinkTemplate;
import com.armada.hyperlink.template.model.vo.HyperlinkTemplateDetailVO;
import com.armada.hyperlink.template.model.vo.HyperlinkTemplateListItemVO;
import com.armada.hyperlink.template.model.vo.HyperlinkTemplateOptionVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** 超链模板 DTO、共享消息结构、实体与 VO 的 MapStruct 转换器。 */
@Mapper(componentModel = "spring")
public interface HyperlinkTemplateConverter {

    /** 按冻结字段序列化按钮 JSON。 */
    ObjectMapper BUTTONS_JSON = new ObjectMapper();

    /** 从创建请求提取可供模板和未来任务共用的消息结构。 */
    HyperlinkMessageContent toContent(HyperlinkTemplateCreateDTO dto);

    /** 从更新请求提取可供模板和未来任务共用的消息结构。 */
    HyperlinkMessageContent toContent(HyperlinkTemplateUpdateDTO dto);

    /** 把已归一化的模板元数据和消息内容转换成待持久化实体。 */
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "templateName", source = "name")
    @Mapping(target = "messageType", source = "content.messageType")
    @Mapping(target = "messageSchemaVersion", source = "content.schemaVersion")
    @Mapping(target = "title", source = "content.title")
    @Mapping(target = "content", source = "content.content")
    @Mapping(target = "linkDescription", source = "content.linkDescription")
    @Mapping(target = "promotionLink", source = "content.promotionLink")
    @Mapping(target = "buttons", source = "content.buttons")
    @Mapping(target = "cardText", source = "content.cardText")
    @Mapping(target = "linkPreviewAssetId", source = "content.linkPreviewAssetId")
    @Mapping(target = "bodyMainAssetId", source = "content.bodyMainAssetId")
    @Mapping(target = "remark", source = "remark")
    HyperlinkTemplate toEntity(String name, String remark, HyperlinkMessageContent content);

    /** 复制模板时只复制业务内容，不复制租户、主键、审计和版本字段。 */
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "templateName", source = "templateName")
    @Mapping(target = "messageType", source = "messageType")
    @Mapping(target = "messageSchemaVersion", source = "messageSchemaVersion")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "content", source = "content")
    @Mapping(target = "linkDescription", source = "linkDescription")
    @Mapping(target = "promotionLink", source = "promotionLink")
    @Mapping(target = "buttons", source = "buttons")
    @Mapping(target = "cardText", source = "cardText")
    @Mapping(target = "linkPreviewAssetId", source = "linkPreviewAssetId")
    @Mapping(target = "bodyMainAssetId", source = "bodyMainAssetId")
    @Mapping(target = "remark", source = "remark")
    HyperlinkTemplate copyBusiness(HyperlinkTemplate source);

    /** 实体转换为列表项，并生成稳定素材读取 URL。 */
    @Mapping(target = "name", source = "templateName")
    @Mapping(target = "linkPreviewAssetUrl", expression = "java(assetUrl(entity.getLinkPreviewAssetId()))")
    @Mapping(target = "bodyMainAssetUrl", expression = "java(assetUrl(entity.getBodyMainAssetId()))")
    @Mapping(target = "taskRefCount", constant = "0L")
    HyperlinkTemplateListItemVO toListItem(HyperlinkTemplate entity);

    /** 实体转换为完整详情，并反序列化按钮 JSON。 */
    @Mapping(target = "name", source = "templateName")
    @Mapping(target = "schemaVersion", source = "messageSchemaVersion")
    @Mapping(target = "linkPreviewAssetUrl", expression = "java(assetUrl(entity.getLinkPreviewAssetId()))")
    @Mapping(target = "bodyMainAssetUrl", expression = "java(assetUrl(entity.getBodyMainAssetId()))")
    @Mapping(target = "taskRefCount", constant = "0L")
    HyperlinkTemplateDetailVO toDetail(HyperlinkTemplate entity);

    /** 实体转换为模板候选。 */
    @Mapping(target = "name", source = "templateName")
    HyperlinkTemplateOptionVO toOption(HyperlinkTemplate entity);

    /** 批量转换列表项。 */
    List<HyperlinkTemplateListItemVO> toListItems(List<HyperlinkTemplate> entities);

    /** 批量转换候选。 */
    List<HyperlinkTemplateOptionVO> toOptions(List<HyperlinkTemplate> entities);

    /** 按钮数组转换为 MySQL JSON 字符串；无按钮也稳定保存空数组。 */
    default String buttonsToJson(List<HyperlinkButton> buttons) {
        try {
            return BUTTONS_JSON.writeValueAsString(buttons == null ? List.of() : buttons);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.VALIDATION, "超链按钮序列化失败");
        }
    }

    /** 按钮 JSON 转换为响应数组；历史空值或坏数据按空数组返回。 */
    default List<HyperlinkButton> buttonsFromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return BUTTONS_JSON.readValue(json, new TypeReference<List<HyperlinkButton>>() {
            });
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    /** 稳定素材 ID 转现有图片内容接口；空 ID 对应空 URL。 */
    default String assetUrl(Long assetId) {
        return assetId == null ? null : "/api/marketing-template-files/" + assetId + "/content";
    }
}
