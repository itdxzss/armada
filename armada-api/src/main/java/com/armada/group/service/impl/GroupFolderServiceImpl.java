package com.armada.group.service.impl;

import com.armada.group.mapper.GroupFolderMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.dto.GroupFolderWriteDTO;
import com.armada.group.model.entity.GroupFolder;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.vo.GroupFolderDeleteVO;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.group.model.vo.GroupFolderVO;
import com.armada.group.model.vo.GroupPoolResourceVO;
import com.armada.group.service.GroupFolderService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeAccess;
import com.armada.task.service.PullTaskGroupOccupancyService;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 群组运营分组业务实现。 */
@Service
public class GroupFolderServiceImpl implements GroupFolderService {

    private static final Logger log = LoggerFactory.getLogger(GroupFolderServiceImpl.class);
    private static final int NAME_MAX_LENGTH = 100;
    private static final int BATCH_MAX = 100;
    private static final String USED_FOLDER_NAME = "已使用群组";
    private static final Set<String> SYSTEM_FOLDER_NAMES = Set.of(USED_FOLDER_NAME, "未分组");

    private final GroupFolderMapper folderMapper;
    private final GroupLinkMapper groupLinkMapper;
    private final PullTaskGroupOccupancyService taskGroupOccupancyService;

    public GroupFolderServiceImpl(
            GroupFolderMapper folderMapper,
            GroupLinkMapper groupLinkMapper,
            PullTaskGroupOccupancyService taskGroupOccupancyService) {
        this.folderMapper = folderMapper;
        this.groupLinkMapper = groupLinkMapper;
        this.taskGroupOccupancyService = taskGroupOccupancyService;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<GroupFolderVO> list(GroupFolderQuery query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "查询参数不能为空");
        }
        query.applyDataScope(DataScopeAccess.requireCurrent());
        long total = folderMapper.countPage(query);
        List<GroupFolderVO> rows = total == 0 ? List.of() : folderMapper.selectPage(query);
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    /** {@inheritDoc} */
    @Override
    public List<GroupFolderOptionVO> options() {
        return folderMapper.selectOptions(DataScopeAccess.requireCurrent());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupFolderVO create(GroupFolderWriteDTO request, long userId) {
        DataScope scope = DataScopeAccess.requireCurrent();
        if (!scope.actorUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "创建人和当前操作者不一致");
        }
        Long ownerUserId = scope.ownerUserIdForCreate();
        String name = normalizeName(request);
        if (folderMapper.selectActiveByNameForOwner(name, ownerUserId) != null) {
            throw duplicateName();
        }

        long now = System.currentTimeMillis();
        GroupFolder deleted = folderMapper.selectDeletedByNameForOwner(name, ownerUserId);
        GroupFolder row = new GroupFolder();
        row.setOwnerUserId(ownerUserId);
        row.setName(name);
        row.setCreatedBy(scope.actorUserId());
        row.setUpdatedAt(now);
        try {
            if (deleted == null) {
                row.setCreatedAt(now);
                folderMapper.insert(row);
                log.info("群组运营分组已创建 id={} name={}", row.getId(), name);
            } else {
                row.setId(deleted.getId());
                row.setCreatedAt(deleted.getCreatedAt());
                if (folderMapper.revive(row) != 1) {
                    throw new BusinessException(ErrorCode.CONFLICT, "群组分组状态已变化，请刷新后重试");
                }
                log.info("群组运营分组已复活 id={} name={}", row.getId(), name);
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateName();
        }
        return new GroupFolderVO(
                row.getId(), ownerUserId, name, false, 0L, row.getCreatedAt(), now);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(long id, GroupFolderWriteDTO request) {
        DataScope scope = DataScopeAccess.requireCurrent();
        GroupFolder current = requireEntity(id, scope);
        if (Boolean.TRUE.equals(current.getSystemBuiltin())) {
            throw new BusinessException(ErrorCode.CONFLICT, "系统分组不允许修改名称");
        }
        String name = normalizeName(request);
        GroupFolder owner = folderMapper.selectAnyByNameForOwner(
                name, current.getOwnerUserId());
        if (owner != null && !current.getId().equals(owner.getId())) {
            throw duplicateName();
        }
        try {
            if (folderMapper.updateName(
                    id, current.getOwnerUserId(), name, System.currentTimeMillis()) != 1) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "群组分组不存在或已删除: " + id);
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateName();
        }
        log.info("群组运营分组已更新 id={} name={}", id, name);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupFolderDeleteVO batchDelete(List<Long> ids) {
        DataScope scope = DataScopeAccess.requireCurrent();
        List<Long> normalizedIds = normalizeIds(ids);
        List<GroupFolder> folders = folderMapper.selectActiveByIdsForUpdate(normalizedIds, scope);
        if (folders.size() != normalizedIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分群组分组不存在或已删除，请刷新后重试");
        }
        if (folders.stream().anyMatch(folder -> Boolean.TRUE.equals(folder.getSystemBuiltin()))) {
            throw new BusinessException(ErrorCode.CONFLICT, "系统分组不允许删除");
        }
        taskGroupOccupancyService.requireFoldersNotInUse(normalizedIds);

        int groupCount = groupLinkMapper.countActiveByFolderIds(normalizedIds, scope);
        long now = System.currentTimeMillis();
        int cleared = groupLinkMapper.clearFolderByFolderIds(normalizedIds, scope, now);
        if (cleared != groupCount) {
            throw new BusinessException(ErrorCode.CONFLICT, "群组分组关系已变化，请刷新后重试");
        }
        int deleted = folderMapper.softDeleteByIds(normalizedIds, scope, now);
        if (deleted != normalizedIds.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "群组分组状态已变化，请刷新后重试");
        }
        log.info("群组运营分组批量删除 folderCount={} ungroupedGroupCount={} ids={}",
                deleted, cleared, normalizedIds);
        return new GroupFolderDeleteVO(deleted, cleared);
    }

    /** {@inheritDoc} */
    @Override
    public GroupFolderOptionVO requireExisting(long id) {
        GroupFolder row = requireCustomFolder(id, DataScopeAccess.requireCurrent());
        return new GroupFolderOptionVO(row.getId(), row.getOwnerUserId(), row.getName());
    }

    /** {@inheritDoc} */
    @Override
    public List<String> usableLinks(long id) {
        GroupFolder row = requireCustomFolder(id, DataScopeAccess.requireCurrent());
        return folderMapper.selectUsableLinks(id, row.getOwnerUserId());
    }

    /** {@inheritDoc} */
    @Override
    public List<GroupPoolResourceVO> usableResources(long id) {
        GroupFolder row = requireCustomFolder(id, DataScopeAccess.requireCurrent());
        return folderMapper.selectUsableResources(id, row.getOwnerUserId());
    }

    /** {@inheritDoc} */
    @Override
    public GroupPoolResourceVO requireUsableResourceForUpdate(long folderId, long groupLinkId) {
        GroupFolder folder = requireCustomFolder(folderId, DataScopeAccess.requireCurrent());
        if (groupLinkId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "群组 ID 必须为正整数");
        }
        GroupPoolResourceVO resource =
                folderMapper.selectUsableResourceForUpdate(
                        folderId, groupLinkId, folder.getOwnerUserId());
        if (resource == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "群组已移出资源池或当前不可用");
        }
        return resource;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void moveToUsed(long groupLinkId) {
        requirePositiveGroupId(groupLinkId);
        DataScope scope = DataScopeAccess.requireCurrent();
        long now = System.currentTimeMillis();
        GroupLink group = requireGroupForUpdate(groupLinkId, scope);
        Long ownerUserId = group.getOwnerUserId();
        folderMapper.upsertUsedSystemFolder(USED_FOLDER_NAME, ownerUserId, now);
        GroupFolder used = folderMapper.selectActiveByNameForOwner(
                USED_FOLDER_NAME, ownerUserId);
        if (used == null || !Boolean.TRUE.equals(used.getSystemBuiltin())) {
            throw new IllegalStateException("系统已使用群组创建失败");
        }
        if (folderMapper.selectActiveByIdsForUpdate(List.of(used.getId()), scope).size() != 1) {
            throw new IllegalStateException("系统已使用群组状态发生变化");
        }
        assignSingleGroup(group, used.getId(), scope, now);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void moveToUngrouped(long groupLinkId) {
        requirePositiveGroupId(groupLinkId);
        DataScope scope = DataScopeAccess.requireCurrent();
        assignSingleGroup(
                requireGroupForUpdate(groupLinkId, scope), null, scope,
                System.currentTimeMillis());
    }

    private void assignSingleGroup(
            GroupLink group, Long folderId, DataScope scope, long now) {
        List<Long> ids = List.of(group.getId());
        if (groupLinkMapper.assignFolder(ids, folderId, scope, now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "群组分组关系已变化，请刷新后重试");
        }
    }

    private GroupLink requireGroupForUpdate(long groupLinkId, DataScope scope) {
        List<GroupLink> groups = groupLinkMapper.selectActiveByIdsForUpdate(
                List.of(groupLinkId), scope);
        if (groups.size() != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "群组不存在或已删除: " + groupLinkId);
        }
        return groups.get(0);
    }

    private static void requirePositiveGroupId(long groupLinkId) {
        if (groupLinkId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "群组 ID 必须为正整数");
        }
    }

    private GroupFolder requireEntity(long id, DataScope scope) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "群组分组 ID 必须为正整数");
        }
        GroupFolder row = folderMapper.selectById(id, scope);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "群组分组不存在或已删除: " + id);
        }
        return row;
    }

    private GroupFolder requireCustomFolder(long id, DataScope scope) {
        GroupFolder row = requireEntity(id, scope);
        if (Boolean.TRUE.equals(row.getSystemBuiltin())) {
            throw new BusinessException(ErrorCode.CONFLICT, "系统分组不能作为任务资源池");
        }
        return row;
    }

    private static String normalizeName(GroupFolderWriteDTO request) {
        if (request == null || request.name() == null || request.name().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "群组分组名称不能为空");
        }
        String name = request.name().trim();
        if (SYSTEM_FOLDER_NAMES.contains(name)) {
            throw new BusinessException(ErrorCode.VALIDATION, "系统分组名称不允许用户创建或修改");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "群组分组名称不能超过" + NAME_MAX_LENGTH + "个字符");
        }
        return name;
    }

    private static List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "ids 数量须为 1.." + BATCH_MAX);
        }
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(ErrorCode.VALIDATION, "群组分组 ID 必须为正整数");
        }
        List<Long> normalizedIds = List.copyOf(new TreeSet<>(ids));
        if (normalizedIds.size() > BATCH_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "ids 数量须为 1.." + BATCH_MAX);
        }
        return normalizedIds;
    }

    private static BusinessException duplicateName() {
        return new BusinessException(ErrorCode.VALIDATION, "群组分组名称已存在");
    }
}
