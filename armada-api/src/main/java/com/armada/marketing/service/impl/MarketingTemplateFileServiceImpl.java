package com.armada.marketing.service.impl;

import com.armada.marketing.asset.service.ResourceAssetImageValidator;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.marketing.model.vo.MarketingTemplateFileContent;
import com.armada.marketing.model.vo.MarketingTemplateFileVO;
import com.armada.marketing.service.MarketingTemplateFileService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 营销模板图片文件与共享素材内容服务实现。
 *
 * <p>保留营销模板既有图片上传和鉴权内容读取能力，同时为营销模板、超链模板绑定共享素材
 * 提供事务内行锁与图片字节校验。普通读取由 MyBatis 租户拦截器隔离；绑定锁要求调用方
 * 已开启事务，避免素材删除与模板绑定并发产生悬空引用。</p>
 */
@Service
public class MarketingTemplateFileServiceImpl implements MarketingTemplateFileService {

    /** 营销模板图片文件数据访问。 */
    private final MarketingTemplateFileMapper mapper;

    /**
     * 创建营销模板图片服务。
     *
     * @param mapper 营销模板图片文件数据访问
     */
    public MarketingTemplateFileServiceImpl(MarketingTemplateFileMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 上传并保存当前租户的营销模板图片。
     *
     * <p>先校验文件和读取实际字节，再将文件名、MIME、字节长度及内容一起持久化；响应只返回
     * 展示元数据和鉴权内容地址，不直接返回图片二进制。</p>
     *
     * @param file 待上传图片
     * @return 已保存图片的展示信息与鉴权内容地址
     * @throws BusinessException 文件为空、MIME 不是图片或读取失败时抛出
     */
    @Override
    public MarketingTemplateFileVO uploadImage(MultipartFile file) {
        // 先做轻量校验，再读取二进制内容，避免无效文件进入后续持久化流程。
        validateImage(file);
        byte[] bytes = readBytes(file);

        // 文件内容、文件名和 MIME 类型一起保存，模板详情回显时不依赖外部对象存储。
        MarketingTemplateFile row = new MarketingTemplateFile();
        row.setOriginalFilename(originalFilename(file));
        row.setContentType(file.getContentType());
        row.setSizeBytes((long) bytes.length);
        row.setContent(bytes);
        row.setAssetName(truncate(originalFilename(file), 128));
        long now = System.currentTimeMillis();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        // tenant_id 由 MyBatis 租户拦截器从 TenantContext 自动注入。
        mapper.insert(row);
        return toVO(row);
    }

    /**
     * 读取当前租户营销模板图片或共享素材的原始内容。
     *
     * <p>主键查询受租户拦截器约束，禁止跨租户读取图片 BLOB。</p>
     *
     * @param id 图片文件 ID
     * @return 图片 MIME 与原始字节
     * @throws BusinessException 图片不存在或不属于当前租户时抛出
     */
    @Override
    public MarketingTemplateFileContent content(Long id) {
        // selectById 同样受租户拦截器约束，避免跨租户读取图片二进制。
        MarketingTemplateFile row = mapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "营销模板图片不存在: " + id);
        }
        return new MarketingTemplateFileContent(row.getContentType(), row.getContent());
    }

    /**
     * 在调用方事务内锁定并读取待绑定素材内容。
     *
     * <p>行锁与后续模板写入处于同一事务，用于阻止并发删除在绑定提交前移除素材。</p>
     *
     * @param id 当前租户素材 ID
     * @return 已锁定素材的 MIME 与原始字节
     * @throws BusinessException 租户上下文缺失，或素材不存在、已删除时抛出
     * @throws org.springframework.transaction.IllegalTransactionStateException 调用方未开启事务时抛出
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MarketingTemplateFileContent lockContentForBinding(Long id) {
        MarketingTemplateFile row = lockExisting(id);
        return new MarketingTemplateFileContent(row.getContentType(), row.getContent());
    }

    /**
     * 在调用方事务内按 ID 升序锁定并校验全部待绑定素材。
     *
     * <p>输入会过滤空值、去重并排序，以固定锁顺序降低死锁风险；每个素材必须存在且能按允许的
     * 图片类型解码。空集合不访问数据库。</p>
     *
     * @param ids 待绑定素材 ID 集合
     * @throws BusinessException 租户上下文缺失，素材不存在、已删除或图片内容不可绑定时抛出
     * @throws org.springframework.transaction.IllegalTransactionStateException 调用方未开启事务时抛出
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAndValidateBindableAssets(Collection<Long> ids) {
        List<Long> normalizedIds = ids == null
                ? List.of()
                : ids.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
        for (Long id : normalizedIds) {
            MarketingTemplateFile row = lockExisting(id);
            ResourceAssetImageValidator.validateBindable(row.getContentType(), row.getContent());
        }
    }

    private MarketingTemplateFile lockExisting(Long id) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        MarketingTemplateFile row = mapper.selectByIdForUpdate(tenantId, id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "图片不存在或已删除");
        }
        return row;
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

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
