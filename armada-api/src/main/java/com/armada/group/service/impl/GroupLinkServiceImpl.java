package com.armada.group.service.impl;

import com.armada.group.converter.GroupConverter;
import com.armada.group.mapper.GroupLinkLabelMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.vo.GroupLinkVO;
import com.armada.group.service.GroupLinkService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 群链接业务实现。
 *
 * <p>租户隔离由 MyBatis 租户拦截器透明完成,本类不手写 tenant_id。</p>
 */
@Service
public class GroupLinkServiceImpl implements GroupLinkService {

    private static final Logger log = LoggerFactory.getLogger(GroupLinkServiceImpl.class);

    /** 批量操作上限:防止一次操作过多造成锁竞争。 */
    private static final int BATCH_MAX = 100;

    private final GroupLinkMapper groupLinkMapper;
    private final GroupLinkLabelMapper labelMapper;
    private final GroupConverter converter;

    public GroupLinkServiceImpl(GroupLinkMapper groupLinkMapper,
                                GroupLinkLabelMapper labelMapper,
                                GroupConverter converter) {
        this.groupLinkMapper = groupLinkMapper;
        this.labelMapper = labelMapper;
        this.converter = converter;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:按 labelId 分页列出该分组下的群链接;先取总数,为 0 时直接返回空页;
     * 分页/筛选全部 SQL 下推,不在内存裁剪。</p>
     */
    @Override
    public PageResult<GroupLinkVO> listByLabel(GroupLinkQuery query) {
        long total = groupLinkMapper.countByLabel(query);
        List<GroupLinkVO> rows = total == 0
                ? List.of()
                : converter.toGroupLinkVOList(groupLinkMapper.selectPageByLabel(query));
        log.debug("群链接分页查询 labelId={} total={}", query.getLabelId(), total);
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:① linkIds 数量须在 1..{@value #BATCH_MAX} 之间、目标分组须存在;
     * ② 迁移前校验 linkIds 全部活跃——活跃数与请求数不一致即整体取消(避免把已删链接误迁);
     * ③ 仅改 group_link.label_id 重挂分组,不动链接的来源批次。全程 {@code @Transactional}。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int migrate(List<Long> linkIds, Long targetLabelId) {
        if (linkIds == null || linkIds.isEmpty() || linkIds.size() > BATCH_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION, "linkIds 数量须为 1.." + BATCH_MAX);
        }
        if (targetLabelId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "目标分组 ID 不能为空");
        }
        // 校验目标分组存在
        if (labelMapper.selectById(targetLabelId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "目标分组不存在: " + targetLabelId);
        }
        // 校验 linkIds 全部活跃
        int activeCount = groupLinkMapper.countActiveByIds(linkIds);
        if (activeCount != linkIds.size()) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "部分群链接不存在或已删除,迁移取消(期望 " + linkIds.size() + " 条,活跃 " + activeCount + " 条)");
        }
        int n = groupLinkMapper.migrateToLabel(linkIds, targetLabelId, System.currentTimeMillis());
        log.info("群链接批量迁移 count={} targetLabelId={}", n, targetLabelId);
        return n;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:ids 数量须在 1..{@value #BATCH_MAX} 之间,超限拒绝防锁竞争;
     * 软删(置 deleted_at)选中的群链接,返回实际软删行数。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > BATCH_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION, "ids 数量须为 1.." + BATCH_MAX);
        }
        int n = groupLinkMapper.softDeleteByIds(ids, System.currentTimeMillis());
        log.info("群链接批量删除 count={} ids={}", n, ids);
        return n;
    }
}
