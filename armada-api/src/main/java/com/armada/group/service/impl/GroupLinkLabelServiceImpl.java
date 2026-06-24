package com.armada.group.service.impl;

import com.armada.group.converter.GroupConverter;
import com.armada.group.mapper.GroupLinkImportBatchMapper;
import com.armada.group.mapper.GroupLinkLabelMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.GroupLinkLabelDTO;
import com.armada.group.model.dto.GroupLinkLabelQuery;
import com.armada.group.model.entity.GroupLinkLabel;
import com.armada.group.model.vo.GroupLinkLabelVO;
import com.armada.group.service.GroupLinkLabelService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * WS链接分组业务实现。
 *
 * <p>租户隔离由 MyBatis 租户拦截器透明完成,本类不手写 tenant_id。</p>
 */
@Service
public class GroupLinkLabelServiceImpl implements GroupLinkLabelService {

    private static final Logger log = LoggerFactory.getLogger(GroupLinkLabelServiceImpl.class);

    /** 批量删除上限:防止一次删除过多造成锁竞争。 */
    private static final int BATCH_DELETE_MAX = 100;

    private final GroupLinkLabelMapper labelMapper;
    private final GroupLinkMapper groupLinkMapper;
    private final GroupLinkImportBatchMapper importBatchMapper;
    private final GroupConverter converter;

    public GroupLinkLabelServiceImpl(GroupLinkLabelMapper labelMapper,
                                     GroupLinkMapper groupLinkMapper,
                                     GroupLinkImportBatchMapper importBatchMapper,
                                     GroupConverter converter) {
        this.labelMapper = labelMapper;
        this.groupLinkMapper = groupLinkMapper;
        this.importBatchMapper = importBatchMapper;
        this.converter = converter;
    }

    @Override
    public PageResult<GroupLinkLabelVO> list(GroupLinkLabelQuery query) {
        long total = labelMapper.countPage(query);
        List<GroupLinkLabelVO> rows = total == 0
                ? List.of()
                : converter.toLabelVOList(labelMapper.selectPage(query));
        log.debug("WS链接分组列表查询 total={} page={} pageSize={}", total, query.getPage(), query.getPageSize());
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupLinkLabelVO create(GroupLinkLabelDTO dto) {
        if (!StringUtils.hasText(dto.name())) {
            throw new BusinessException(ErrorCode.VALIDATION, "分组名称不能为空");
        }
        if (labelMapper.selectActiveByName(dto.name()) != null) {
            throw new BusinessException(ErrorCode.VALIDATION, "分组名称已存在: " + dto.name());
        }
        GroupLinkLabel deleted = labelMapper.selectDeletedByName(dto.name());
        GroupLinkLabel row = new GroupLinkLabel();
        row.setName(dto.name());
        row.setRegion(dto.region());
        row.setRemark(dto.remark());

        if (deleted != null) {
            // 复活软删分组:复原 deleted_at + 更新基本信息
            row.setId(deleted.getId());
            labelMapper.reviveById(deleted.getId());
            labelMapper.updateProfile(row);
            log.info("WS链接分组复活 id={} name={}", deleted.getId(), dto.name());
        } else {
            labelMapper.insert(row);
            log.info("WS链接分组已创建 id={} name={}", row.getId(), dto.name());
        }

        return new GroupLinkLabelVO(row.getId(), dto.name(), dto.region(), dto.remark(), 0L, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, GroupLinkLabelDTO dto) {
        GroupLinkLabel cur = labelMapper.selectById(id);
        if (cur == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分组不存在: " + id);
        }
        GroupLinkLabel other = labelMapper.selectActiveByName(dto.name());
        if (other != null && !other.getId().equals(id)) {
            throw new BusinessException(ErrorCode.VALIDATION, "分组名称已存在: " + dto.name());
        }
        GroupLinkLabel row = new GroupLinkLabel();
        row.setId(id);
        row.setName(dto.name());
        row.setRegion(dto.region());
        row.setRemark(dto.remark());
        labelMapper.updateProfile(row);
        log.info("WS链接分组已更新 id={} name={}", id, dto.name());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > BATCH_DELETE_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "ids 数量须为 1.." + BATCH_DELETE_MAX);
        }
        // 级联软删:群链接 → 导入批次 → 分组
        groupLinkMapper.softDeleteByLabelIds(ids);
        importBatchMapper.softDeleteByLabelIds(ids);
        int n = labelMapper.softDeleteByIds(ids);
        log.info("WS链接分组批量删除 count={} ids={}", n, ids);
        return n;
    }
}
