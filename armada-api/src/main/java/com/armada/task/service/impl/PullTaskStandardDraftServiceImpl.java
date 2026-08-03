package com.armada.task.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.vo.PullTaskStandardDraftVO;
import com.armada.task.model.vo.PullTaskStandardExecutionRowVO;
import com.armada.task.model.vo.PullTaskStandardFileResultVO;
import com.armada.task.model.vo.PullTaskStandardLinkLineVO;
import com.armada.task.service.PullTaskStandardDraftService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 普通群链接创建页草稿编排实现。
 *
 * <p>本类<b>刻意不标 {@code @Transactional}</b>：匹配追加流程里包含最坏 40 秒的公开邀请页
 * 预检，事务包住外部 HTTP 会让数据库连接被网络阻塞占用。全部写操作委托给
 * {@link PullTaskStandardDraftWriter}，事务边界落在那个 bean 上。</p>
 */
@Service
public class PullTaskStandardDraftServiceImpl implements PullTaskStandardDraftService {

    private static final Logger log = LoggerFactory.getLogger(PullTaskStandardDraftServiceImpl.class);

    /** 用户还没有草稿时返回的空视图。 */
    private static final PullTaskStandardDraftVO EMPTY_VIEW = new PullTaskStandardDraftVO(
            null, null, List.of(), List.of(), List.of(), 0, 0, 0);

    private final PullTaskMapper pullTaskMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskStandardDraftWriter writer;

    /**
     * 创建草稿编排服务。
     *
     * @param pullTaskMapper  任务主表数据访问
     * @param executionMapper 执行行数据访问
     * @param writer          草稿事务写入组件
     */
    public PullTaskStandardDraftServiceImpl(PullTaskMapper pullTaskMapper,
                                            PullTaskGroupExecutionMapper executionMapper,
                                            PullTaskStandardDraftWriter writer) {
        this.pullTaskMapper = pullTaskMapper;
        this.executionMapper = executionMapper;
        this.writer = writer;
    }

    @Override
    public PullTaskStandardDraftVO current(long userId) {
        PullTask draft = pullTaskMapper.selectLatestDraftByCreator(userId);
        if (draft == null) {
            return EMPTY_VIEW;
        }
        return toView(draft, List.of(), List.of(), 0, 0);
    }

    @Override
    public PullTaskStandardDraftVO removeRow(long rowId, long userId) {
        PullTask draft = requireDraft(userId);
        writer.removeRow(draft.getId(), rowId);
        log.info("创建页移除执行行 taskId={} rowId={} operatorId={}", draft.getId(), rowId, userId);
        return toView(draft, List.of(), List.of(), 0, 0);
    }

    @Override
    public PullTaskStandardDraftVO clear(long userId) {
        PullTask draft = requireDraft(userId);
        writer.clearAll(draft.getId());
        log.info("创建页清除全部执行行 taskId={} operatorId={}", draft.getId(), userId);
        return toView(draft, List.of(), List.of(), 0, 0);
    }

    /**
     * 取当前用户的草稿，没有则拒绝操作。
     *
     * @param userId 当前登录用户 ID
     * @return 草稿任务行
     * @throws BusinessException 草稿不存在时
     */
    private PullTask requireDraft(long userId) {
        PullTask draft = pullTaskMapper.selectLatestDraftByCreator(userId);
        if (draft == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "当前没有可编辑的创建页草稿");
        }
        return draft;
    }

    /**
     * 读回执行行并组装草稿视图。
     *
     * @param draft              草稿任务行
     * @param linkLines          本次请求的链接逐行结果；回读与编辑场景为空
     * @param fileResults        本次请求的逐文件结果；回读与编辑场景为空
     * @param remainingLinkCount 本次请求后仍未匹配的有效链接数
     * @param ignoredFileCount   本次被忽略的文件数
     * @return 草稿视图
     */
    PullTaskStandardDraftVO toView(PullTask draft,
                                   List<PullTaskStandardLinkLineVO> linkLines,
                                   List<PullTaskStandardFileResultVO> fileResults,
                                   int remainingLinkCount,
                                   int ignoredFileCount) {
        List<PullTaskStandardExecutionRowVO> rows = executionMapper.selectByTaskId(draft.getId())
                .stream()
                .map(PullTaskStandardDraftServiceImpl::toRowView)
                .toList();
        return new PullTaskStandardDraftVO(draft.getId(), draft.getVersion(), rows,
                linkLines, fileResults, rows.size(), remainingLinkCount, ignoredFileCount);
    }

    /**
     * 执行行实体转出参。
     *
     * @param row 执行行实体
     * @return 执行行出参
     */
    private static PullTaskStandardExecutionRowVO toRowView(PullTaskGroupExecution row) {
        return new PullTaskStandardExecutionRowVO(row.getId(), row.getSeq(),
                row.getNormalizedLink(), row.getSourceLinkLineNo(), row.getSourceFileName(),
                row.getTotalLineCount(), row.getValidMemberCount(),
                row.getInvalidLineCount(), row.getDuplicateLineCount());
    }
}
