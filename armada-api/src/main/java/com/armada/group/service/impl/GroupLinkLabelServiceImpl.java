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

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:先取总数,为 0 时直接返回空页、省掉一次必然空结果的列表查询;
     * 分页与筛选全部由 Mapper 的 SQL 下推,不在内存里裁剪。</p>
     */
    @Override
    public PageResult<GroupLinkLabelVO> list(GroupLinkLabelQuery query) {
        long total = labelMapper.countPage(query);
        List<GroupLinkLabelVO> rows = total == 0
                ? List.of()
                : converter.toLabelVOList(labelMapper.selectPage(query));
        log.debug("WS链接分组列表查询 total={} page={} pageSize={}", total, query.getPage(), query.getPageSize());
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:① 名称非空且活分组内不可重名;② 若命中同名的【软删】分组则复活它
     * (复原 deleted_at + 更新基本信息)而非插新行——配合 group_link_label 的 plain 唯一键,
     * 避免同名占键冲突;否则插入新行;③ 落库后按主键回查一次,拿到库表 DEFAULT 生成的
     * createdAt/updatedAt 再组装出参(否则这两个服务端时间戳为 null)。全程 {@code @Transactional},
     * 任一步失败整体回滚。</p>
     */
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

        // 读回库行,确保 createdAt/updatedAt 返回真实数据库写入时间(非 null)
        GroupLinkLabel saved = labelMapper.selectById(row.getId());
        return new GroupLinkLabelVO(
                saved.getId(),
                dto.name(),
                dto.region(),
                dto.remark(),
                0L,
                saved.getCreatedAt() == null ? null : saved.getCreatedAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli(),
                saved.getUpdatedAt() == null ? null : saved.getUpdatedAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:校验名称非空、分组存在;重名校验排除自身(同名但 id 相同视为未改名),
     * 仅当命中【他人】的同名活分组才报重复。只更新名称/区域/备注三项基本信息。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, GroupLinkLabelDTO dto) {
        if (!StringUtils.hasText(dto.name())) {
            throw new BusinessException(ErrorCode.VALIDATION, "分组名称不能为空");
        }
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

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:ids 数量须在 1..{@value #BATCH_DELETE_MAX} 之间,超限拒绝以防一次删太多引发锁竞争;
     * 按【群链接 → 导入批次 → 分组】顺序级联软删(均置 deleted_at),保证下游先于父级失效。
     * 返回实际软删的分组行数。</p>
     */
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
