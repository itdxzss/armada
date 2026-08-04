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
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            throw new BusinessException(ErrorCode.VALIDATION, "分组名称已存在: " + name);
        }

        long now = System.currentTimeMillis();
        GroupFolder deleted = mapper.selectDeletedByName(name);
        GroupFolder row = new GroupFolder();
        row.setName(name);
        row.setCreatedBy(userId);
        row.setUpdatedAt(now);
        if (deleted == null) {
            row.setCreatedAt(now);
            mapper.insert(row);
            log.info("群组运营分组已创建 id={} name={}", row.getId(), name);
        } else {
            row.setId(deleted.getId());
            row.setCreatedAt(deleted.getCreatedAt());
            mapper.revive(row);
            log.info("群组运营分组已复活 id={} name={}", row.getId(), name);
        }
        return new GroupFolderVO(row.getId(), name, 0L, row.getCreatedAt(), now);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(long id, GroupFolderWriteDTO request) {
        requireExisting(id);
        String name = normalizeName(request);
        GroupFolder other = mapper.selectActiveByName(name);
        if (other != null && other.getId() != id) {
            throw new BusinessException(ErrorCode.VALIDATION, "分组名称已存在: " + name);
        }
        GroupFolder row = new GroupFolder();
        row.setId(id);
        row.setName(name);
        row.setUpdatedAt(System.currentTimeMillis());
        mapper.updateName(row);
        log.info("群组运营分组已更新 id={} name={}", id, name);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupFolderDeleteVO batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > BATCH_DELETE_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "ids 数量须为 1.." + BATCH_DELETE_MAX);
        }
        List<Long> uniqueIds = new LinkedHashSet<>(ids).stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        if (uniqueIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "ids 不能为空");
        }
        long now = System.currentTimeMillis();
        int ungroupedCount = mapper.clearGroupLinks(uniqueIds, now);
        int deletedCount = mapper.softDeleteByIds(uniqueIds, now);
        log.info("群组运营分组批量删除 deletedCount={} ungroupedCount={}",
                deletedCount, ungroupedCount);
        return new GroupFolderDeleteVO(deletedCount, ungroupedCount);
    }

    /** {@inheritDoc} */
    @Override
    public GroupFolderOptionVO requireExisting(long id) {
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

    private String normalizeName(GroupFolderWriteDTO request) {
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
}
