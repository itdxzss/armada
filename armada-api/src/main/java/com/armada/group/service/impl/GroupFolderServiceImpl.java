package com.armada.group.service.impl;

import com.armada.group.converter.GroupConverter;
import com.armada.group.mapper.GroupFolderMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.GroupFolderDTO;
import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.entity.GroupFolder;
import com.armada.group.model.vo.GroupFolderDeleteResultVO;
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

/**
 * 群组列表运营分组业务实现。
 */
@Service
public class GroupFolderServiceImpl implements GroupFolderService {

    private static final Logger log = LoggerFactory.getLogger(GroupFolderServiceImpl.class);

    private static final int NAME_MAX_LENGTH = 64;
    private static final int BATCH_MAX = 100;

    private final GroupFolderMapper folderMapper;
    private final GroupLinkMapper groupLinkMapper;
    private final GroupConverter converter;

    public GroupFolderServiceImpl(GroupFolderMapper folderMapper,
                                  GroupLinkMapper groupLinkMapper,
                                  GroupConverter converter) {
        this.folderMapper = folderMapper;
        this.groupLinkMapper = groupLinkMapper;
        this.converter = converter;
    }

    /**
     * 分页查询运营分组，关联群数由 SQL 下推统计。
     */
    @Override
    public PageResult<GroupFolderVO> list(GroupFolderQuery query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "查询参数不能为空");
        }
        long total = folderMapper.countPage(query);
        List<GroupFolderVO> rows = total == 0
                ? List.of()
                : converter.toFolderVOList(folderMapper.selectPage(query));
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    /**
     * 返回筛选器和批量分组弹窗使用的全量活跃选项。
     */
    @Override
    public List<GroupFolderOptionVO> options() {
        return folderMapper.selectOptions().stream()
                .map(converter::toFolderOptionVO)
                .toList();
    }

    /**
     * 创建运营分组；命中同名软删除行时复活原 ID，避免唯一键分叉。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupFolderVO create(GroupFolderDTO dto) {
        String name = normalizeName(dto);
        if (folderMapper.selectActiveByName(name) != null) {
            throw duplicateName();
        }

        GroupFolder deleted = folderMapper.selectDeletedByName(name);
        long now = System.currentTimeMillis();
        if (deleted != null) {
            if (folderMapper.reviveById(deleted.getId(), now) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "群组分组状态已变化，请刷新后重试");
            }
            log.info("群组运营分组已复活 id={} name={}", deleted.getId(), name);
            return new GroupFolderVO(deleted.getId(), name, 0L, deleted.getCreatedAt(), now);
        }

        GroupFolder row = new GroupFolder();
        row.setName(name);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            folderMapper.insert(row);
        } catch (DuplicateKeyException exception) {
            throw duplicateName();
        }
        log.info("群组运营分组已创建 id={} name={}", row.getId(), name);
        return new GroupFolderVO(row.getId(), name, 0L, now, now);
    }

    /**
     * 修改运营分组名称；任何其他行（含软删除行）占用目标名称时均拒绝。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, GroupFolderDTO dto) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "群组分组 ID 不能为空");
        }
        String name = normalizeName(dto);
        GroupFolder current = folderMapper.selectById(id);
        if (current == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "群组分组不存在或已删除: " + id);
        }
        GroupFolder owner = folderMapper.selectAnyByName(name);
        if (owner != null && !id.equals(owner.getId())) {
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

    /**
     * 删除分组时先解除群组关系，再软删除分组；整个过程全有或全无。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupFolderDeleteResultVO batchDelete(List<Long> ids) {
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
        int deleted = folderMapper.softDeleteByIds(normalizedIds, now);
        if (deleted != normalizedIds.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "群组分组状态已变化，请刷新后重试");
        }
        log.info("群组运营分组批量删除 folderCount={} ungroupedGroupCount={} ids={}",
                deleted, cleared, normalizedIds);
        return new GroupFolderDeleteResultVO(deleted, cleared);
    }

    private static String normalizeName(GroupFolderDTO dto) {
        if (dto == null || dto.name() == null || dto.name().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "群组分组名称不能为空");
        }
        String name = dto.name().trim();
        if (name.length() > NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "群组分组名称不能超过" + NAME_MAX_LENGTH + "个字符");
        }
        return name;
    }

    private static List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "ids 数量须为 1.." + BATCH_MAX);
        }
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(ErrorCode.VALIDATION, "群组分组 ID 必须为正整数");
        }
        List<Long> normalizedIds = List.copyOf(new TreeSet<>(ids));
        if (normalizedIds.size() > BATCH_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION, "ids 数量须为 1.." + BATCH_MAX);
        }
        return normalizedIds;
    }

    private static BusinessException duplicateName() {
        return new BusinessException(ErrorCode.VALIDATION, "群组分组名称已存在");
    }
}
