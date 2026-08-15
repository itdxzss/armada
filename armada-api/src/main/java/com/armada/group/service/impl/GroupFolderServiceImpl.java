package com.armada.group.service.impl;

import com.armada.group.mapper.GroupFolderMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.dto.GroupFolderWriteDTO;
import com.armada.group.model.entity.GroupFolder;
import com.armada.group.model.vo.GroupFolderDeleteVO;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.group.model.vo.GroupFolderVO;
import com.armada.group.service.GroupFolderService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.util.List;
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

    private final GroupFolderMapper folderMapper;
    private final GroupLinkMapper groupLinkMapper;
    private final GroupCurrentLocalPersistence currentLocalPersistence;

    public GroupFolderServiceImpl(
            GroupFolderMapper folderMapper,
            GroupLinkMapper groupLinkMapper,
            GroupCurrentLocalPersistence currentLocalPersistence) {
        this.folderMapper = folderMapper;
        this.groupLinkMapper = groupLinkMapper;
        this.currentLocalPersistence = currentLocalPersistence;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<GroupFolderVO> list(GroupFolderQuery query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "查询参数不能为空");
        }
        long total = folderMapper.countPage(query);
        List<GroupFolderVO> rows = total == 0 ? List.of() : folderMapper.selectPage(query);
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    /** {@inheritDoc} */
    @Override
    public List<GroupFolderOptionVO> options() {
        return folderMapper.selectOptions();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupFolderVO create(GroupFolderWriteDTO request, long userId) {
        String name = normalizeName(request);
        if (folderMapper.selectActiveByName(name) != null) {
            throw duplicateName();
        }

        long now = System.currentTimeMillis();
        GroupFolder deleted = folderMapper.selectDeletedByName(name);
        GroupFolder row = new GroupFolder();
        row.setName(name);
        row.setCreatedBy(userId);
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
        return new GroupFolderVO(row.getId(), name, 0L, row.getCreatedAt(), now);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(long id, GroupFolderWriteDTO request) {
        GroupFolder current = requireEntity(id);
        String name = normalizeName(request);
        GroupFolder owner = folderMapper.selectAnyByName(name);
        if (owner != null && !current.getId().equals(owner.getId())) {
            throw duplicateName();
        }
        try {
            if (folderMapper.updateName(id, name, System.currentTimeMillis()) != 1) {
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
        List<Long> normalizedIds = normalizeIds(ids);
        List<GroupFolder> folders = folderMapper.selectActiveByIdsForUpdate(normalizedIds);
        if (folders.size() != normalizedIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分群组分组不存在或已删除，请刷新后重试");
        }

        int groupCount = groupLinkMapper.countActiveByFolderIds(normalizedIds);
        long now = System.currentTimeMillis();
        int cleared = groupLinkMapper.clearFolderByFolderIds(normalizedIds, now);
        if (cleared != groupCount) {
            throw new BusinessException(ErrorCode.CONFLICT, "群组分组关系已变化，请刷新后重试");
        }
        currentLocalPersistence.applyLegacyFolderDeletion(normalizedIds, now);
        int deleted = folderMapper.softDeleteByIds(normalizedIds, now);
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
        GroupFolder row = requireEntity(id);
        return new GroupFolderOptionVO(row.getId(), row.getName());
    }

    /** {@inheritDoc} */
    @Override
    public List<String> usableLinks(long id) {
        requireEntity(id);
        return folderMapper.selectUsableLinks(id);
    }

    private GroupFolder requireEntity(long id) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "群组分组 ID 必须为正整数");
        }
        GroupFolder row = folderMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "群组分组不存在或已删除: " + id);
        }
        return row;
    }

    private static String normalizeName(GroupFolderWriteDTO request) {
        if (request == null || request.name() == null || request.name().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "群组分组名称不能为空");
        }
        String name = request.name().trim();
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
