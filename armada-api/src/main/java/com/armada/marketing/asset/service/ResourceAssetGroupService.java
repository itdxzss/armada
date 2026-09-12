package com.armada.marketing.asset.service;

import com.armada.marketing.asset.mapper.ResourceAssetGroupMapper;
import com.armada.marketing.asset.model.dto.ResourceAssetMoveDTO;
import com.armada.marketing.asset.model.entity.ResourceAssetGroup;
import com.armada.marketing.asset.model.vo.ResourceAssetGroupVO;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import com.armada.marketing.asset.model.enums.ResourceAssetScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 分组创建、归属调整及保留素材的删除事务。 */
@Service
public class ResourceAssetGroupService {
    /** 分组名称上限，与数据库列一致。 */
    private static final int MAX_NAME_LENGTH = 64;
    /** 单次批量移组上限，覆盖素材列表最大一页。 */
    private static final int MAX_BATCH_SIZE = 100;
    /** 分组与归属数据访问。 */
    private final ResourceAssetGroupMapper groupMapper;
    /** 复用素材行锁与租户检查。 */
    private final MarketingTemplateFileMapper fileMapper;

    /** @param groupMapper 分组 Mapper @param fileMapper 素材 Mapper */
    public ResourceAssetGroupService(ResourceAssetGroupMapper groupMapper, MarketingTemplateFileMapper fileMapper) {
        this.groupMapper = groupMapper;
        this.fileMapper = fileMapper;
    }

    /** @return 当前租户全部分组，包含空分组 */
    public List<ResourceAssetGroupVO> list(ResourceAssetScope scope) {
        requireTenant();
        return groupMapper.selectAll(scope.getCode());
    }

    /** @param name 分组名称 @return 新分组；空名称、超长或重复时抛出业务异常 */
    public ResourceAssetGroupVO create(String name, ResourceAssetScope scope) {
        requireTenant();
        if (name == null || name.isBlank() || name.trim().length() > MAX_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION, "分组名称不能为空且最长 64 个字符");
        }
        ResourceAssetGroup group = new ResourceAssetGroup();
        group.setGroupName(name.trim());
        group.setScope(scope.getCode());
        group.setCreatedAt(System.currentTimeMillis());
        try {
            groupMapper.insert(group);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.CONFLICT, "分组名称已存在");
        }
        return new ResourceAssetGroupVO(group.getId(), group.getGroupName());
    }

    /** @param id 分组 ID；原子解除所有归属再删除分组，图片和引用全部保留 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, ResourceAssetScope scope) {
        if (id == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "未分组不能删除");
        }
        lockTarget(id, scope);
        groupMapper.ungroup(id, scope.getCode());
        groupMapper.delete(id);
    }

    /** @param request 素材 ID 与目标分组；任何一项不可访问时整批失败 */
    @Transactional(rollbackFor = Exception.class)
    public void move(ResourceAssetMoveDTO request, ResourceAssetScope scope) {
        requireTenant();
        if (request == null || request.assetIds() == null || request.assetIds().isEmpty()
                || request.assetIds().size() > MAX_BATCH_SIZE
                || request.assetIds().stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择 1 至 100 张素材");
        }
        lockTarget(request.groupId(), scope);
        List<Long> ids = request.assetIds().stream().distinct().sorted().toList();
        for (Long id : ids) {
            if (fileMapper.selectIdByIdForUpdate(id) == null
                    || fileMapper.selectAssetMetadataById(id, scope.getCode()) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "素材不存在或无权访问，请刷新后重试");
            }
        }
        groupMapper.deleteRefs(ids, scope.getCode());
        if (request.groupId() != null) {
            groupMapper.move(ids, request.groupId(), System.currentTimeMillis(), scope.getCode());
        }
    }

    /** @param groupId 目标分组，null 表示未分组；必须在调用方写事务内执行，防止与删组竞争 */
    public void lockTarget(Long groupId, ResourceAssetScope scope) {
        requireTenant();
        if (groupId != null && (groupId <= 0 || groupMapper.lockById(groupId, scope.getCode()) == null)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分组不存在或已删除，请刷新后重试");
        }
    }

    private static void requireTenant() {
        if (TenantContext.get() == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
    }
}
