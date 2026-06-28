package com.armada.marketing.service.impl;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.dto.CreateMarketingTaskDTO;
import com.armada.marketing.model.dto.MarketingSelectionDTO;
import com.armada.marketing.model.dto.MarketingTaskQuery;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import com.armada.marketing.model.vo.MarketingTaskDetailVO;
import com.armada.marketing.model.vo.MarketingTaskTargetVO;
import com.armada.marketing.model.vo.MarketingTaskVO;
import com.armada.marketing.service.MarketingTaskService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 营销任务第一阶段实现。
 *
 * <p>当前 checkpoint 只负责把页面提交的任务配置与「账号×群组」目标持久化,让列表/详情可读。
 * 即使入参选择了立即启动,本类也只把主表状态置为发送中;真实发消息、在线检测、异常群跳过和重试
 * 后续由发送引擎 checkpoint 接管。</p>
 *
 * <p>跨域事实来源保持单一:营销模板仍读 {@code marketing_template};账号/群事实在建目标时
 * 从 {@code account}、{@code group_link}、{@code group_link_preview} 拼快照,不在任务表复制更多运行态。</p>
 */
@Service
public class MarketingTaskServiceImpl implements MarketingTaskService {

    private static final Logger log = LoggerFactory.getLogger(MarketingTaskServiceImpl.class);

    private final MarketingTaskMapper taskMapper;
    private final MarketingTemplateMapper templateMapper;

    /**
     * 注入营销任务 Mapper 与营销模板 Mapper。
     *
     * <p>任务 Mapper 负责本聚合读写;模板 Mapper 只用于校验模板存在并读取模板名称快照,
     * 不读取或复制模板正文。</p>
     *
     * @param taskMapper     营销任务与目标明细数据访问
     * @param templateMapper 营销模板数据访问
     */
    public MarketingTaskServiceImpl(MarketingTaskMapper taskMapper, MarketingTemplateMapper templateMapper) {
        this.taskMapper = taskMapper;
        this.templateMapper = templateMapper;
    }

    /**
     * 分页查询营销任务列表。
     *
     * <p>按查询对象里的 ID、任务名称关键词、状态、最后发送时间范围过滤;SQL 层分页,
     * 返回列表行 VO。列表不加载 target 明细,避免任务列表查询被明细行数量放大。</p>
     *
     * @param query 分页与筛选条件
     * @return 当前页营销任务列表
     */
    @Override
    public PageResult<MarketingTaskVO> listTasks(MarketingTaskQuery query) {
        long total = taskMapper.countPage(query);
        // 与其它列表服务保持一致:total=0 时不再查 page rows,避免一次必然空结果的 SELECT。
        List<MarketingTaskVO> rows = total == 0
                ? List.of()
                : taskMapper.selectPage(query).stream().map(MarketingTaskServiceImpl::toVO).toList();
        log.info("营销任务列表查询 total={} page={} pageSize={}", total, query.getPage(), query.getPageSize());
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    /**
     * 新建营销任务并生成账号×群组目标明细。
     *
     * <p>本方法会校验基础入参、确认营销模板存在、把前端提交的账号→群组选择拆成
     * `marketing_task_target` 执行目标,再写入 `marketing_task` 主表。若启动模式是
     * `IMMEDIATE`,只把任务状态置为发送中并写 `started_at`,不调用协议层、不发送消息。</p>
     *
     * @param request 新建任务表单入参
     * @return 创建后的任务列表行视图
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingTaskVO createTask(CreateMarketingTaskDTO request) {
        // 事务顺序固定:先校验共享事实,再生成目标快照,最后插主表和明细。
        // 任何一个目标不可用都整单失败,避免页面看到"半个任务"。
        validateRequest(request);
        MarketingTemplate template = requireTemplate(request.marketingTemplateId());
        long now = System.currentTimeMillis();
        List<MarketingTaskTarget> targets = buildTargets(request, now);
        MarketingTask task = buildTask(request, template, targets, now);
        taskMapper.insertTask(task);
        // 主表自增 id 回填后才能写 target.marketing_task_id。
        for (MarketingTaskTarget target : targets) {
            target.setMarketingTaskId(task.getId());
        }
        taskMapper.insertTargets(targets);
        log.info("营销任务已创建 id={} targets={} status={}", task.getId(), targets.size(), task.getStatus());
        return toVO(taskMapper.selectTaskById(task.getId()));
    }

    /**
     * 查询营销任务详情。
     *
     * <p>详情由任务主表和目标明细组成:主表提供配置、状态和计数,目标明细提供每个账号×群组
     * 的号码、群 JID、群链接、群名和发送结果字段。任务不存在时抛业务 404。</p>
     *
     * @param id 营销任务 ID
     * @return 营销任务详情,包含目标明细列表
     */
    @Override
    public MarketingTaskDetailVO getDetail(Long id) {
        // 详情用主表 + target 明细两次查询,不在列表 SQL 里提前聚合明细,保持列表轻量。
        MarketingTask task = requireTask(id);
        List<MarketingTaskTargetVO> targets = taskMapper.selectTargetsByTaskId(id)
                .stream().map(MarketingTaskServiceImpl::toTargetVO).toList();
        log.info("营销任务详情查询 id={} targets={}", id, targets.size());
        return toDetailVO(task, targets);
    }

    private void validateRequest(CreateMarketingTaskDTO request) {
        // 只校验页面表单本身能确定的必填和数值约束;账号/群/模板是否真的可用在后续查库校验。
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销任务不能为空");
        }
        if (!StringUtils.hasText(request.taskName())) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务名称不能为空");
        }
        if (request.accountGroupId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择账号分组");
        }
        if (request.marketingTemplateId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择营销模板");
        }
        if (positive(request.sendPerRound()) < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "单次发送数量必须为正整数");
        }
        if (positive(request.sendIntervalSeconds()) < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "发送间隔必须为正整数");
        }
        if (request.selections() == null || request.selections().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "请至少选择一个发送账号和群组");
        }
    }

    private MarketingTemplate requireTemplate(Long id) {
        // 模板是素材唯一事实源。创建任务只保存模板 id/name 快照,不复制正文和按钮。
        MarketingTemplate template = templateMapper.selectById(id);
        if (template == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "营销模板不存在: " + id);
        }
        return template;
    }

    private MarketingTask requireTask(Long id) {
        MarketingTask task = taskMapper.selectTaskById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "营销任务不存在: " + id);
        }
        return task;
    }

    private List<MarketingTaskTarget> buildTargets(CreateMarketingTaskDTO request, long now) {
        // 前端按账号分组提交 account -> groupLinkIds,后台落库按执行粒度拆成 account x group 一行。
        // LinkedHashSet 既去重,又保留前端选择顺序,详情默认按插入顺序展示。
        Set<String> seenPairs = new LinkedHashSet<>();
        List<MarketingTaskTarget> targets = new ArrayList<>();
        for (MarketingSelectionDTO selection : request.selections()) {
            appendSelectionTargets(request.accountGroupId(), selection, seenPairs, targets, now);
        }
        if (targets.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "请至少选择一个可执行群组");
        }
        return targets;
    }

    private void appendSelectionTargets(Long accountGroupId, MarketingSelectionDTO selection, Set<String> seenPairs,
                                        List<MarketingTaskTarget> targets, long now) {
        // 一个 selection 代表一个发言账号。空账号或空群组是前端状态不一致,直接拒绝。
        if (selection == null || selection.accountId() == null
                || selection.groupLinkIds() == null || selection.groupLinkIds().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号和群组选择不能为空");
        }
        for (Long groupLinkId : selection.groupLinkIds()) {
            // 重复 pair 静默跳过:这不是业务错误,只是前端重复提交或用户重复勾选的防御。
            if (groupLinkId == null || !seenPairs.add(selection.accountId() + ":" + groupLinkId)) {
                continue;
            }
            targets.add(toTarget(requireCandidate(accountGroupId, selection.accountId(), groupLinkId), now));
        }
    }

    private MarketingTargetCandidateRow requireCandidate(Long accountGroupId, Long accountId, Long groupLinkId) {
        // 目标候选必须同时满足:账号存在且属于本次选择的分组、群入口未软删、群预览有 group_jid。
        // group_jid 是协议层发送寻址必需字段,没有它时不能等到发送阶段才失败。
        MarketingTargetCandidateRow row = taskMapper.selectTargetCandidate(accountGroupId, accountId, groupLinkId);
        if (row == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号或群组不可用: account=" + accountId + ", group=" + groupLinkId);
        }
        if (!StringUtils.hasText(row.getGroupJid())) {
            throw new BusinessException(ErrorCode.VALIDATION, "目标群缺少群JID: " + groupLinkId);
        }
        return row;
    }

    private MarketingTaskTarget toTarget(MarketingTargetCandidateRow row, long now) {
        // target 保存的是执行时需要稳定展示/寻址的快照。账号或群名后续变更不回写历史任务明细。
        MarketingTaskTarget target = new MarketingTaskTarget();
        target.setAccountId(row.getAccountId());
        target.setAccountPhone(row.getAccountPhone());
        target.setGroupLinkId(row.getGroupLinkId());
        target.setGroupJid(row.getGroupJid());
        target.setGroupLinkUrl(row.getGroupLinkUrl());
        target.setGroupName(row.getGroupName());
        target.setStatus(MarketingTaskStatus.PENDING.code());
        target.setSentMessageCount(0);
        target.setFailedMessageCount(0);
        target.setRetryCount(0);
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        return target;
    }

    private MarketingTask buildTask(CreateMarketingTaskDTO request, MarketingTemplate template,
                                    List<MarketingTaskTarget> targets, long now) {
        // 立即启动在当前 checkpoint 只改变主表状态和 started_at;发送计数仍保持 0。
        MarketingTaskStatus status = MarketingTaskStatus.fromStartMode(request.startMode());
        MarketingTask task = new MarketingTask();
        task.setTaskName(request.taskName().trim());
        task.setAccountGroupId(request.accountGroupId());
        task.setAccountGroupName(snapshotName(request.accountGroupName(), "账号分组-" + request.accountGroupId()));
        task.setMarketingTemplateId(template.getId());
        task.setMarketingTemplateName(template.getTemplateName());
        task.setStatus(status.code());
        // 三个计数含义不同:
        // selectedAccountCount=去重账号数,targetGroupCount=去重群数,targetPairCount=真实执行目标行数。
        task.setSelectedAccountCount(distinctAccountCount(targets));
        task.setTargetGroupCount(distinctGroupCount(targets));
        task.setTargetPairCount(targets.size());
        task.setSentMessageCount(0);
        task.setFailedMessageCount(0);
        task.setSendPerRound(positive(request.sendPerRound()));
        task.setSendIntervalSeconds(positive(request.sendIntervalSeconds()));
        task.setOnlineCheckEnabled(Boolean.TRUE.equals(request.onlineCheckEnabled()));
        task.setAbnormalGroupSkipped(Boolean.TRUE.equals(request.abnormalGroupSkipped()));
        task.setAutoRetryEnabled(Boolean.TRUE.equals(request.autoRetryEnabled()));
        // 一期需求只表达"失败后自动重试一次";后续若做可配置次数再扩 DTO。
        task.setRetryLimit(Boolean.TRUE.equals(request.autoRetryEnabled()) ? 1 : 0);
        task.setRemark(request.remark());
        task.setStartedAt(status == MarketingTaskStatus.SENDING ? now : null);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    private static int positive(Integer value) {
        return value == null ? 0 : value;
    }

    private static String snapshotName(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static int distinctAccountCount(List<MarketingTaskTarget> targets) {
        return (int) targets.stream().map(MarketingTaskTarget::getAccountId).distinct().count();
    }

    private static int distinctGroupCount(List<MarketingTaskTarget> targets) {
        return (int) targets.stream().map(MarketingTaskTarget::getGroupLinkId).distinct().count();
    }

    private static MarketingTaskVO toVO(MarketingTask task) {
        return new MarketingTaskVO(task.getId(), task.getTaskName(), task.getAccountGroupId(), task.getAccountGroupName(),
                task.getMarketingTemplateId(), task.getMarketingTemplateName(), task.getStatus(),
                task.getSelectedAccountCount(), task.getTargetGroupCount(), task.getTargetPairCount(),
                task.getSentMessageCount(), task.getFailedMessageCount(), task.getSendPerRound(),
                task.getSendIntervalSeconds(), task.getOnlineCheckEnabled(), task.getAbnormalGroupSkipped(),
                task.getAutoRetryEnabled(), task.getRetryLimit(), task.getRemark(), task.getStartedAt(),
                task.getLastSentAt(), task.getFinishedAt(), task.getCreatedAt(), task.getUpdatedAt());
    }

    private static MarketingTaskTargetVO toTargetVO(MarketingTaskTarget target) {
        return new MarketingTaskTargetVO(target.getId(), target.getAccountId(), target.getAccountPhone(),
                target.getGroupLinkId(), target.getGroupJid(), target.getGroupLinkUrl(), target.getGroupName(),
                target.getStatus(), target.getSentMessageCount(), target.getFailedMessageCount(), target.getRetryCount(),
                target.getLastAttemptAt(), target.getLastSentAt(), target.getLastReason());
    }

    private static MarketingTaskDetailVO toDetailVO(MarketingTask task, List<MarketingTaskTargetVO> targets) {
        return new MarketingTaskDetailVO(task.getId(), task.getTaskName(), task.getAccountGroupId(), task.getAccountGroupName(),
                task.getMarketingTemplateId(), task.getMarketingTemplateName(), task.getStatus(),
                task.getSelectedAccountCount(), task.getTargetGroupCount(), task.getTargetPairCount(),
                task.getSentMessageCount(), task.getFailedMessageCount(), task.getSendPerRound(),
                task.getSendIntervalSeconds(), task.getOnlineCheckEnabled(), task.getAbnormalGroupSkipped(),
                task.getAutoRetryEnabled(), task.getRetryLimit(), task.getRemark(), task.getStartedAt(),
                task.getLastSentAt(), task.getFinishedAt(), task.getCreatedAt(), task.getUpdatedAt(), targets);
    }
}
