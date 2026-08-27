package com.armada.marketing.service.impl;

import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.marketing.model.vo.MarketingTemplateFileContent;
import com.armada.marketing.model.vo.MarketingTemplateFileVO;
import com.armada.marketing.service.MarketingTemplateFileService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeAccess;
import java.io.IOException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 营销模板图片文件服务实现。
 */
@Service
public class MarketingTemplateFileServiceImpl implements MarketingTemplateFileService {

    private final MarketingTemplateFileMapper mapper;

    public MarketingTemplateFileServiceImpl(MarketingTemplateFileMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public MarketingTemplateFileVO uploadImage(MultipartFile file) {
        // 先做轻量校验，再读取二进制内容，避免无效文件进入后续持久化流程。
        validateImage(file);
        byte[] bytes = readBytes(file);

        // 文件内容、文件名和 MIME 类型一起保存，模板详情回显时不依赖外部对象存储。
        MarketingTemplateFile row = new MarketingTemplateFile();
        row.setOwnerUserId(DataScopeAccess.requireCurrent().ownerUserIdForCreate());
        row.setOriginalFilename(originalFilename(file));
        row.setContentType(file.getContentType());
        row.setSizeBytes((long) bytes.length);
        row.setContent(bytes);
        row.setCreatedAt(System.currentTimeMillis());
        // tenant_id 由 MyBatis 租户拦截器从 TenantContext 自动注入。
        mapper.insert(row);
        return toVO(row);
    }

    @Override
    public MarketingTemplateFileContent content(Long id) {
        DataScope scope = DataScopeAccess.requireCurrent();
        MarketingTemplateFile row = mapper.selectByIdForScope(id, scope);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "营销模板图片不存在: " + id);
        }
        return new MarketingTemplateFileContent(row.getContentType(), row.getContent());
    }

    private void validateImage(MultipartFile file) {
        // 空文件通常来自未选择文件或表单字段异常，直接返回业务校验错误。
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择图片");
        }
        String contentType = file.getContentType();
        // 这里只接受浏览器上传声明为 image/* 的文件，其他素材类型由模板文本字段处理。
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new BusinessException(ErrorCode.VALIDATION, "只能上传图片文件");
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            // 使用实际读取到的字节长度入库，避免仅依赖请求头里的 size 元数据。
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "图片读取失败");
        }
    }

    private String originalFilename(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        // 原始文件名只作为展示信息保存，缺失时给一个稳定的默认值。
        return StringUtils.hasText(originalFilename) ? originalFilename.trim() : "image";
    }

    private MarketingTemplateFileVO toVO(MarketingTemplateFile row) {
        // 列表/上传响应不直接返回二进制，前端通过 contentUrl 再按需拉取 Blob。
        return new MarketingTemplateFileVO(
                row.getId(),
                row.getOriginalFilename(),
                row.getContentType(),
                row.getSizeBytes(),
                // 前端保存后用该路径通过带租户头的 Blob 请求回显图片。
                contentUrl(row.getId()));
    }

    private String contentUrl(Long id) {
        return "/api/marketing-template-files/" + id + "/content";
    }
}
