package com.armada.marketing.asset.converter;

import com.armada.marketing.asset.model.vo.ResourceAssetVO;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.util.StringUtils;

/** 素材文件实体与素材库稳定响应之间的 MapStruct 转换器。 */
@Mapper(componentModel = "spring")
public interface ResourceAssetConverter {

    /** 素材内容读取接口的稳定路径前缀。 */
    String CONTENT_PATH_PREFIX = "/api/resource-assets/";

    /**
     * 将不含 BLOB 的文件元数据、批量标签和引用统计合并为素材响应。
     *
     * @param file 当前租户的素材文件元数据
     * @param tags 当前素材的标签，已按关系创建顺序排序
     * @param referenceCount 当前素材在有效模板或任务中的去重引用数
     * @return 管理页和选择器共用的素材响应
     */
    @Mapping(target = "assetName", expression = "java(displayName(file))")
    @Mapping(
            target = "contentUrl",
            expression = "java(CONTENT_PATH_PREFIX + file.getId() + \"/content\")")
    @Mapping(target = "tags", expression = "java(java.util.List.copyOf(tags))")
    @Mapping(
            target = "updatedAt",
            expression = "java(file.getUpdatedAt() == null ? file.getCreatedAt() : file.getUpdatedAt())")
    ResourceAssetVO toVO(
            MarketingTemplateFile file,
            List<String> tags,
            long referenceCount);

    /**
     * 为存量文件生成稳定展示名称，优先使用素材业务名称。
     *
     * @param file 素材文件元数据
     * @return 非空展示名称
     */
    default String displayName(MarketingTemplateFile file) {
        if (StringUtils.hasText(file.getAssetName())) {
            return file.getAssetName();
        }
        if (StringUtils.hasText(file.getOriginalFilename())) {
            return file.getOriginalFilename();
        }
        return "素材 #" + file.getId();
    }
}
