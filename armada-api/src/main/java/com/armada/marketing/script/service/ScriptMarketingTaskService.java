package com.armada.marketing.script.service;

import com.armada.marketing.asset.model.enums.ResourceAssetScope;
import com.armada.group.service.GroupDetailService;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountService;
import com.armada.marketing.model.vo.ScriptQualificationVO;
import com.armada.marketing.converter.ScriptMarketingConverter;
import com.armada.marketing.mapper.ScriptMarketingTaskMapper;
import com.armada.marketing.mapper.ScriptMarketingGroupMapper;
import com.armada.marketing.mapper.ScriptMarketingSendRecordMapper;
import com.armada.marketing.model.dto.ScriptMarketingSaveDTO;
import com.armada.marketing.model.dto.ScriptMarketingQuery;
import com.armada.marketing.model.entity.ScriptMarketingGroup;
import com.armada.marketing.model.entity.ScriptMarketingTask;
import com.armada.marketing.model.vo.ScriptMarketingTaskVO;
import com.armada.marketing.model.vo.ScriptMarketingDetailVO;
import com.armada.marketing.model.vo.ScriptMarketingSendRecordVO;
import com.armada.marketing.service.MarketingTemplateFileService;
import com.armada.shared.paging.PageQuery;
import com.armada.shared.response.PageResult;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.DRAFT;

/** 独立剧本任务的配置和只读详情；任务按租户及创建用户隔离。 */
@Service
public class ScriptMarketingTaskService {
    private final ScriptMarketingTaskMapper tasks;
    private final ScriptMarketingGroupMapper groups;
    private final ScriptMarketingSendRecordMapper records;
    private final ScriptMarketingConverter converter;
    private final ScriptMarketingContentService content;
    private final GroupDetailService groupDetails;
    private final MarketingTemplateFileService assets;
    private final AccountGroupService accountGroups;
    private final ScriptQualificationService qualification;
    private final AccountService accounts;
    /** 注入配置持久化与现有群、素材校验服务。 */
    public ScriptMarketingTaskService(ScriptMarketingTaskMapper tasks, ScriptMarketingGroupMapper groups,
            ScriptMarketingSendRecordMapper records, ScriptMarketingConverter converter,
            ScriptMarketingContentService content, GroupDetailService groupDetails,
            MarketingTemplateFileService assets, AccountGroupService accountGroups,
            ScriptQualificationService qualification, AccountService accounts) {
        this.tasks = tasks; this.groups = groups; this.records = records; this.converter = converter;
        this.content = content; this.groupDetails = groupDetails; this.assets = assets;
        this.accountGroups = accountGroups; this.qualification = qualification;
        this.accounts = accounts;
    }
    /** 只返回当前用户任务，分页和结果计数在 SQL 中完成。 */
    public PageResult<ScriptMarketingTaskVO> list(ScriptMarketingQuery query, Long owner) {
        return PageResult.of(tasks.page(query, owner), query.getPage(), query.getPageSize(), tasks.count(query, owner));
    }
    /** 返回固定配置与至多 100 个群进度，记录通过独立分页接口查询。 */
    public ScriptMarketingDetailVO detail(Long id, Long owner) {
        var task = requireOwned(tasks.find(id), owner);
        var steps = content.decode(task.getStepsJson());
        var targets = groups.list(id);
        var accountIds = new LinkedHashSet<Long>();
        steps.forEach(step -> { if (step.accountId() != null) accountIds.add(step.accountId()); });
        targets.forEach(group -> accountIds.addAll(content.decodeBindings(group.getBindingsJson()).values()));
        return new ScriptMarketingDetailVO(tasks.summary(id), steps,
                targets.stream().map(converter::toGroupVO).toList(), accounts.getPhonesByIds(List.copyOf(accountIds)));
    }
    /** 逐项结果按 SQL 分页，复用任务所有者边界。 */
    public PageResult<ScriptMarketingSendRecordVO> records(Long id, PageQuery query, Long owner) {
        requireOwned(tasks.find(id), owner);
        var page = records.page(id, query);
        var phones = accounts.getPhonesByIds(page.stream().map(row -> row.getAccountId()).distinct().toList());
        return PageResult.of(page.stream().map(row -> converter.toRecordVO(row, phones.get(row.getAccountId()))).toList(),
                query.getPage(), query.getPageSize(), records.count(id));
    }
    /** 新建草稿；保存不发送，启动动作另行显式触发。 */
    @Transactional(rollbackFor = Exception.class)
    public ScriptMarketingDetailVO create(ScriptMarketingSaveDTO dto, Long owner) {
        var task = prepare(dto);
        assets.lockAndValidateBindableAssets(dto.steps().stream().map(s -> s.message().imageFileId()).toList(), ResourceAssetScope.SCRIPT);
        task.setTenantId(TenantContext.get()); task.setCreatedBy(owner); task.setStatus(DRAFT);
        task.setCreatedAt(task.getUpdatedAt());
        tasks.insert(task);
        saveGroups(task, dto.groupLinkIds());
        return detail(task.getId(), owner);
    }
    /** 表单检查不保存也不发送；群与账号分组仍由服务端确认当前租户访问权。 */
    public ScriptQualificationVO check(ScriptMarketingSaveDTO dto) {
        var task = prepare(dto);
        var targets = resolveGroups(dto.groupLinkIds());
        return qualification.inspect(task.getAccountGroupId(), dto.steps(), targets, false).report();
    }
    /** 重新检查已保存任务；启动后校验原绑定，不产生新的随机身份。 */
    public ScriptQualificationVO checkSaved(Long id, Long owner) {
        var task = requireOwned(tasks.find(id), owner);
        if (task.getAccountGroupId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "存量固定账号任务请完成当前执行，新建任务使用分组检查");
        }
        return qualification.inspect(task.getAccountGroupId(), content.decode(task.getStepsJson()),
                groups.list(id), task.getStatus() != DRAFT).report();
    }
    /** 仅草稿允许修改；行锁与启动串行，启动后内容和顺序固定。 */
    @Transactional(rollbackFor = Exception.class)
    public ScriptMarketingDetailVO update(Long id, ScriptMarketingSaveDTO dto, Long owner) {
        var old = requireOwned(tasks.lock(id), owner);
        if (old.getStatus() != DRAFT) throw new BusinessException(ErrorCode.CONFLICT, "启动后不能修改内容和顺序");
        var task = prepare(dto); task.setId(id); task.setTenantId(old.getTenantId());
        assets.lockAndValidateBindableAssets(dto.steps().stream().map(s -> s.message().imageFileId()).toList(), ResourceAssetScope.SCRIPT);
        tasks.updateDraft(task); groups.deleteDraftGroups(id); saveGroups(task, dto.groupLinkIds());
        return detail(id, owner);
    }
    /** 任务查询统一隐藏他人及其他租户记录。 */
    public static ScriptMarketingTask requireOwned(ScriptMarketingTask task, Long owner) {
        if (task == null || !Objects.equals(task.getCreatedBy(), owner)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在或无权访问");
        }
        return task;
    }
    private ScriptMarketingTask prepare(ScriptMarketingSaveDTO dto) {
        if (dto == null || dto.taskName() == null || dto.taskName().isBlank() || dto.taskName().length() > 100
                || dto.intervalSeconds() == null || dto.intervalSeconds() < 1 || dto.intervalSeconds() > 86400
                || dto.groupLinkIds() == null || dto.groupLinkIds().isEmpty() || dto.groupLinkIds().size() > 100
                || dto.groupLinkIds().stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ErrorCode.VALIDATION, "请填写任务名称、1–86400 秒间隔和 1–100 个目标群");
        }
        accountGroups.requireExisting(dto.accountGroupId());
        content.validateRoles(dto.steps());
        if (dto.steps().stream().anyMatch(s -> s.accountId() != null)) {
            throw new BusinessException(ErrorCode.VALIDATION, "管理员与推手由系统启动时按群分配，无需手动选择账号");
        }
        var task = converter.toTask(dto);
        long now = System.currentTimeMillis();
        task.setStartAt(dto.startAt() == null ? now : dto.startAt());
        if (dto.endAt() != null && dto.endAt() <= Math.max(now, task.getStartAt())) {
            throw new BusinessException(ErrorCode.VALIDATION, "截止时间必须晚于开始时间和当前时间");
        }
        task.setStepsJson(content.encode(dto.steps())); task.setUpdatedAt(now);
        return task;
    }
    private void saveGroups(ScriptMarketingTask task, List<Long> ids) {
        for (var group : resolveGroups(ids)) {
            group.setTenantId(task.getTenantId()); group.setTaskId(task.getId());
            group.setNextStep(0); group.setNextAt(task.getStartAt()); group.setRemainingWaitMs(0L);
            groups.insert(group);
        }
    }
    private List<ScriptMarketingGroup> resolveGroups(List<Long> ids) {
        var seen = new HashSet<String>();
        var result = new java.util.ArrayList<ScriptMarketingGroup>();
        for (Long id : ids) {
            var detail = groupDetails.detail(id);
            if (detail.groupJid() == null || !detail.groupJid().endsWith("@g.us")) {
                throw new BusinessException(ErrorCode.VALIDATION, "目标群缺少有效的群 JID，请先同步群资料");
            }
            if (!seen.add(detail.groupJid())) throw new BusinessException(ErrorCode.VALIDATION, "目标群不能重复");
            var group = new ScriptMarketingGroup();
            group.setGroupLinkId(id);
            group.setGroupJid(detail.groupJid()); group.setGroupName(detail.groupName());
            result.add(group);
        }
        return List.copyOf(result);
    }
}
