package com.armada.marketing.service;

import com.armada.marketing.model.vo.MarketingTemplateFileContent;
import com.armada.marketing.model.vo.MarketingTemplateFileVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 营销模板图片文件服务。
 */
public interface MarketingTemplateFileService {

    /** 上传营销模板图片。 */
    MarketingTemplateFileVO uploadImage(MultipartFile file);

    /** 读取营销模板图片内容。 */
    MarketingTemplateFileContent content(Long id);
}
