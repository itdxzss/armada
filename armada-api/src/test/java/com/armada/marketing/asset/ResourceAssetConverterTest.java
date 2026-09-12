package com.armada.marketing.asset;

import com.armada.marketing.asset.converter.ResourceAssetConverter;
import com.armada.marketing.asset.model.enums.ResourceAssetScope;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 历史图片的内容地址必须携带查看时的业务上下文。 */
class ResourceAssetConverterTest {
    @Test
    void sharedImageContentUrlUsesRequestScope() {
        ResourceAssetConverter converter = Mappers.getMapper(ResourceAssetConverter.class);
        MarketingTemplateFile file = new MarketingTemplateFile();
        file.setId(9L);
        assertEquals("/api/resource-assets/9/content?scope=SCRIPT",
                converter.toVO(file, List.of(), 0, ResourceAssetScope.SCRIPT).contentUrl());
        assertEquals("/api/resource-assets/9/content?scope=HYPERLINK",
                converter.toVO(file, List.of(), 0, ResourceAssetScope.HYPERLINK).contentUrl());
    }
}
