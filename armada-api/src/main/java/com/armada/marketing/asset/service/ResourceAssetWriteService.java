package com.armada.marketing.asset.service;

import com.armada.marketing.asset.mapper.ResourceAssetTagMapper;
import com.armada.marketing.asset.model.entity.ResourceAssetTag;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 素材文件与标签关系的短事务写入边界。 */
@Service
public class ResourceAssetWriteService {

    /** 素材文件数据访问。 */
    private final MarketingTemplateFileMapper fileMapper;
    /** 素材标签和关系数据访问。 */
    private final ResourceAssetTagMapper tagMapper;

    /**
     * 创建素材写事务服务。
     *
     * @param fileMapper 素材文件数据访问
     * @param tagMapper 素材标签数据访问
     */
    public ResourceAssetWriteService(
            MarketingTemplateFileMapper fileMapper,
            ResourceAssetTagMapper tagMapper) {
        this.fileMapper = fileMapper;
        this.tagMapper = tagMapper;
    }

    /**
     * 在同一事务中创建素材文件、标签字典和标签关系。
     *
     * @param file 已完成字节校验的素材实体
     * @param tags 已归一化公共标签
     * @return 新素材 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(MarketingTemplateFile file, List<String> tags) {
        fileMapper.insert(file);
        replaceTags(file.getId(), tags, file.getCreatedAt());
        return file.getId();
    }

    /**
     * 锁定素材后原子更新名称并整体替换标签关系。
     *
     * @param id 素材 ID
     * @param assetName 已归一化素材名称
     * @param tags 已归一化完整标签集合
     * @param updatedAt 更新时间，epoch 毫秒
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, String assetName, List<String> tags, long updatedAt) {
        lockExisting(id);
        if (fileMapper.updateAssetMetadata(id, assetName, updatedAt) != 1) {
            throw notFound();
        }
        replaceTags(id, tags, updatedAt);
    }

    /**
     * 锁定素材、检查引用后软删除文件并清理标签关系。
     *
     * @param id 素材 ID
     * @param deletedAt 删除时间，epoch 毫秒
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, long deletedAt) {
        Long tenantId = requireTenant();
        if (fileMapper.selectByIdForUpdate(tenantId, id) == null) {
            throw notFound();
        }
        long references = fileMapper.countReferences(tenantId, id);
        if (references > 0) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "该素材仍被 " + references + " 处模板或任务引用，不能删除");
        }
        if (fileMapper.softDeleteAsset(id, deletedAt) != 1) {
            throw notFound();
        }
        tagMapper.deleteRefsByFileId(id);
    }

    private void replaceTags(Long fileId, List<String> tags, long now) {
        tagMapper.deleteRefsByFileId(fileId);
        if (tags.isEmpty()) {
            return;
        }
        for (String name : tags) {
            ResourceAssetTag tag = new ResourceAssetTag();
            tag.setTagName(name);
            tag.setCreatedAt(now);
            tagMapper.insertIgnore(tag);
        }
        Map<String, ResourceAssetTag> tagsByName = tagMapper.selectByNames(tags).stream()
                .collect(Collectors.toMap(ResourceAssetTag::getTagName, Function.identity()));
        for (String name : tags) {
            ResourceAssetTag tag = tagsByName.get(name);
            if (tag == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "素材标签写入失败");
            }
            tagMapper.insertRefIgnore(fileId, tag.getId(), now);
        }
    }

    private MarketingTemplateFile lockExisting(Long id) {
        MarketingTemplateFile file = fileMapper.selectByIdForUpdate(requireTenant(), id);
        if (file == null) {
            throw notFound();
        }
        return file;
    }

    private static Long requireTenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return tenantId;
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "图片不存在或已删除");
    }
}
