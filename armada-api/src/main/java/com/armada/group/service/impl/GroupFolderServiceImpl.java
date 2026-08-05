package com.armada.group.service.impl;

import com.armada.group.mapper.GroupFolderMapper;
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
import org.springframework.util.StringUtils;

/** 群组运营分组业务实现。 */
@Service
public class GroupFolderServiceImpl implements GroupFolderService {

    private static final Logger log = LoggerFactory.getLogger(GroupFolderServiceImpl.class);
    private static final int NAME_MAX_LENGTH = 100;
    private static final int BATCH_DELETE_MAX = 100;

    private final GroupFolderMapper mapper;

    public GroupFolderServiceImpl(GroupFolderMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<GroupFolderVO> list(GroupFolderQuery query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "查询参数不能为空");
        }
        long total = mapper.countPage(query);
        List<GroupFolderVO> rows = total == 0 ? List.of() : mapper.selectPage(query);
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    /** {@inheritDoc} */
    @Override
    public List<GroupFolderOptionVO> options() {
        return mapper.selectOptions();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupFolderVO create(GroupFolderWriteDTO request, long userId) {
        String name = normalizeName(request);
        if (mapper.selectActiveByName(name) != null) {
            throw duplicateName();
        }

        long now = System.currentTimeMillis();
        GroupFolder deleted = mapper.selectDeletedByName(name);
        GroupFolder row = new GroupFolder();
        row.setName(name);
        row.setCreatedBy(userId);
        row.setUpdatedAt(now);
        try {
            if (deleted == null) {
                row.setCreatedAt(now);
                mapper.insert(row);
                log.info("群组运营分组已创建 id={} name={}", row.getId(), name);
            } else {
                row.setId(deleted.getId());
                row.setCreatedAt(deleted.getCreatedAt());
                if (mapper.revive(row) != 1) {
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
        requireExisting(id);
        String name = normalizeName(request);
        GroupFolder owner = mapper.selectAnyByName(name);
        if (owner != null && owner.getId() != id) {
            throw duplicateName();
        }

        GroupFolder row = new GroupFolder();
        row.setId(id);
        row.setName(name);
        row.setUpdatedAt(System.currentTimeMillis());
        try {
            if (mapper.updateName(row) != 1) {
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
        List<GroupFolder> folders = mapper.selectActiveByIdsForUpdate(normalizedIds);
        if (folders.size() != normalizedIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分群组分组不存在或已删除，请刷新后重试");
        }

        long now = System.currentTimeMillis();
        int ungroupedCount = mapper.clearGroupLinks(normalizedIds, now);
        int deletedCount = mapper.softDeleteByIds(normalizedIds, now);
        if (deletedCount != normalizedIds.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "群组分组状态已变化，请刷新后重试");
        }
        log.info("群组运营分组批量删除 deletedCount={} ungroupedCount={} ids={}",
                deletedCount, ungroupedCount, normalizedIds);
        return new GroupFolderDeleteVO(deletedCount, ungroupedCount);
    }

    /** {@inheritDoc} */
    @Override
    public GroupFolderOptionVO requireExisting(long id) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "群组分组 ID 必须为正整数");
        }
        GroupFolder row = mapper.selectActiveById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "群组分组不存在: " + id);
        }
        return new GroupFolderOptionVO(row.getId(), row.getName());
    }

    /** {@inheritDoc} */
    @Override
    public List<String> usableLinks(long id) {
        requireExisting(id);
        return mapper.selectUsableLinks(id);
    }

    private static String normalizeName(GroupFolderWriteDTO request) {
        String name = request == null ? null : request.name();
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ErrorCode.VALIDATION, "分组名称不能为空");
        }
        String normalized = name.trim();
        if (normalized.length() > NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "分组名称不能超过 " + NAME_MAX_LENGTH + " 个字符");
        }
        return normalized;
    }

    private static List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "ids 数量须为 1.." + BATCH_DELETE_MAX);
        }
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(ErrorCode.VALIDATION, "群组分组 ID 必须为正整数");
        }
        List<Long> normalizedIds = List.copyOf(new TreeSet<>(ids));
        if (normalizedIds.size() > BATCH_DELETE_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "ids 数量须为 1.." + BATCH_DELETE_MAX);
        }
        return normalizedIds;
    }

    private static BusinessException duplicateName() {
        return new BusinessException(ErrorCode.VALIDATION, "分组名称已存在");
    }
}
