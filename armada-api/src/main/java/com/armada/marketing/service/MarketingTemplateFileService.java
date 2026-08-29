package com.armada.marketing.service;

import com.armada.marketing.model.vo.MarketingTemplateFileContent;
import com.armada.marketing.model.vo.MarketingTemplateFileVO;
import java.util.Collection;
import org.springframework.web.multipart.MultipartFile;

/**
 * 营销模板图片文件服务。
 */
public interface MarketingTemplateFileService {

    /**
     * 上传营销模板图片。
     *
     * @param file 待上传图片
     * @return 已保存图片的展示信息
     */
    MarketingTemplateFileVO uploadImage(MultipartFile file);

    /**
     * 读取当前租户营销模板图片内容。
     *
     * @param id 图片文件 ID
     * @return 图片 MIME 与原始字节
     */
    MarketingTemplateFileContent content(Long id);

    /**
     * 在调用方事务内锁定并返回素材字节，供模板绑定校验。
     *
     * @param id 当前租户素材 ID
     * @return 已锁定素材的 MIME 与字节
     */
    MarketingTemplateFileContent lockContentForBinding(Long id);

    /**
     * 在调用方事务内按 ID 升序锁定并校验全部非空素材，防止绑定与删除并发产生悬空引用。
     *
     * @param ids 待绑定的素材 ID 集合；空集合不执行查询
     */
    void lockAndValidateBindableAssets(Collection<Long> ids);
}
