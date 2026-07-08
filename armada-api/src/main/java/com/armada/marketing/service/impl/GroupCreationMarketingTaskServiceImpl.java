package com.armada.marketing.service.impl;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.marketing.mapper.GroupCreationMarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.dto.CreateGroupCreationMarketingTaskDTO;
import com.armada.marketing.model.dto.GroupCreationMarketingMaterialDTO;
import com.armada.marketing.model.dto.GroupCreationMarketingTaskQuery;
import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.armada.marketing.model.entity.GroupCreationMarketingTask;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.enums.GroupCreationMarketingItemStatus;
import com.armada.marketing.model.enums.GroupCreationMarketingTaskStatus;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.armada.marketing.model.vo.GroupCreationMarketingExportFile;
import com.armada.marketing.model.vo.GroupCreationMarketingExportRow;
import com.armada.marketing.model.vo.GroupCreationMarketingItemVO;
import com.armada.marketing.model.vo.GroupCreationMarketingTaskDetailVO;
import com.armada.marketing.model.vo.GroupCreationMarketingTaskVO;
import com.armada.marketing.service.GroupCreationMarketingTaskService;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 建群营销任务业务实现。
 *
 * <p>负责创建任务、按账号与料子生成执行项、过滤可用账号、停止未终态执行项以及生成导出文件。
 * 协议层建群和营销发送不在创建接口内同步执行,由后台 worker 按执行项状态异步推进。</p>
 */
@Service
public class GroupCreationMarketingTaskServiceImpl implements GroupCreationMarketingTaskService {

    /** 建群营销任务 Mapper。 */
    private final GroupCreationMarketingTaskMapper mapper;

    /** 营销模板 Mapper,用于创建任务时校验并快照模板名称。 */
    private final MarketingTemplateMapper templateMapper;

    /** 普通营销任务 Mapper,用于停止建群营销时同步停止关联营销任务。 */
    private final MarketingTaskMapper marketingTaskMapper;

    /** 建群营销 Excel 导出写入器。 */
    private final GroupCreationMarketingExportWorkbookWriter exportWorkbookWriter;

    /**
     * 注入建群营销任务依赖组件。
     *
     * @param mapper               建群营销任务 Mapper
     * @param templateMapper       营销模板 Mapper
     * @param marketingTaskMapper  普通营销任务 Mapper
     * @param exportWorkbookWriter Excel 导出写入器
     */
    public GroupCreationMarketingTaskServiceImpl(GroupCreationMarketingTaskMapper mapper,
                                                 MarketingTemplateMapper templateMapper,
                                                 MarketingTaskMapper marketingTaskMapper,
                                                 GroupCreationMarketingExportWorkbookWriter exportWorkbookWriter) {
        this.mapper = mapper;
        this.templateMapper = templateMapper;
        this.marketingTaskMapper = marketingTaskMapper;
        this.exportWorkbookWriter = exportWorkbookWriter;
    }

    /**
     * 创建建群营销任务并生成待执行明细。
     *
     * <p>创建时会读取营销模板快照,过滤账号分组内在线可用账号,清洗每个料子文件中的手机号。
     * 账号和有效料子按顺序一一匹配,不能匹配到账号的料子会计入 unmatchedFileCount。</p>
     *
     * @param request 创建任务请求
     * @return 创建后的任务详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupCreationMarketingTaskDetailVO createTask(CreateGroupCreationMarketingTaskDTO request) {
        validateRequest(request);
        MarketingTemplate template = requireTemplate(request.marketingTemplateId());
        List<GroupCreationMarketingAccountCandidate> accounts =
                usableAccounts(mapper.selectAccountCandidatesByGroupId(request.accountGroupId()));
        List<ValidMaterial> materials = validMaterials(request.materials());
        int matchedCount = Math.min(accounts.size(), materials.size());
        if (matchedCount == 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "没有可执行的账号和料子文件匹配");
        }
        long now = System.currentTimeMillis();
        GroupCreationMarketingTask task = buildTask(request, template, matchedCount,
                Math.max(materials.size() - accounts.size(), 0), now);
        mapper.insertTask(task);
        List<GroupCreationMarketingItem> items = buildItems(task.getId(), accounts, materials, matchedCount, request, now);
        mapper.insertItems(items);
        return getDetail(task.getId());
    }

    /**
     * 分页查询建群营销任务。
     *
     * <p>null 查询对象会被规整为空查询,分页和筛选全部下推到 SQL。</p>
     *
     * @param query 查询条件和分页参数
     * @return 建群营销任务分页列表
     */
    @Override
    public PageResult<GroupCreationMarketingTaskVO> listTasks(GroupCreationMarketingTaskQuery query) {
        GroupCreationMarketingTaskQuery normalized = query == null ? new GroupCreationMarketingTaskQuery() : query;
        long total = mapper.countPage(normalized);
        List<GroupCreationMarketingTaskVO> rows = total == 0
                ? List.of()
                : mapper.selectPage(normalized).stream().map(GroupCreationMarketingTaskServiceImpl::toVO).toList();
        return PageResult.of(rows, normalized.getPage(), normalized.getPageSize(), total);
    }

    /**
     * 查询建群营销任务详情。
     *
     * <p>详情包含任务主表快照和按文件顺序排序的执行项明细;任务不存在时抛业务异常。</p>
     *
     * @param id 任务 ID
     * @return 建群营销任务详情
     */
    @Override
    public GroupCreationMarketingTaskDetailVO getDetail(Long id) {
        GroupCreationMarketingTask task = mapper.selectTaskById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "建群营销任务不存在: " + id);
        }
        List<GroupCreationMarketingItemVO> items = mapper.selectItemsByTaskId(id)
                .stream()
                .map(GroupCreationMarketingTaskServiceImpl::toItemVO)
                .toList();
        return toDetailVO(task, items);
    }

    /**
     * 查询账号分组内可用于建群营销的候选账号。
     *
     * <p>候选账号必须有协议账号 ID、账号状态正常、登录在线、未风控且未禁言。</p>
     *
     * @param accountGroupId 账号分组 ID
     * @return 可用账号候选列表
     */
    @Override
    public List<GroupCreationMarketingAccountCandidate> accountCandidates(Long accountGroupId) {
        if (accountGroupId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择账号分组");
        }
        return usableAccounts(mapper.selectAccountCandidatesByGroupId(accountGroupId));
    }

    /**
     * 停止建群营销任务并放弃未终态执行项。
     *
     * <p>停止时先统计并放弃待处理、建群中和营销发送中的执行项,再更新主任务放弃数和状态。
     * 若任务已创建关联的普通营销任务,会同步调用普通营销任务停止 SQL。</p>
     *
     * @param id 任务 ID
     * @return 实际更新的任务主表行数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int stopTask(Long id) {
        GroupCreationMarketingTask task = mapper.selectTaskById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "建群营销任务不存在: " + id);
        }
        long now = System.currentTimeMillis();
        int abandonedDelta = mapper.countStoppableItems(id);
        mapper.stopStoppableItems(id, "TASK_STOPPED", "任务已停止", now);
        int updated = mapper.stopTask(id, GroupCreationMarketingTaskStatus.STOPPED.code(), abandonedDelta, now);
        if (updated > 0 && task.getMarketingTaskId() != null) {
            marketingTaskMapper.stopTask(task.getMarketingTaskId(), now);
        }
        return updated;
    }

    /**
     * 导出建群营销任务统计文件。
     *
     * <p>导出前会规整并去重任务 ID,没有可导出明细时抛业务异常,文件内容由专用 writer 生成。</p>
     *
     * @param ids 任务 ID 列表
     * @return Excel 文件名、content-type 和二进制内容
     */
    @Override
    public GroupCreationMarketingExportFile exportTasks(List<Long> ids) {
        List<Long> normalizedIds = normalizeExportIds(ids);
        List<GroupCreationMarketingExportRow> rows = mapper.selectExportRowsByTaskIds(normalizedIds);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "选中的任务没有可导出的建群明细");
        }
        Instant exportedAt = Instant.now();
        return new GroupCreationMarketingExportFile(
                exportWorkbookWriter.filename(exportedAt),
                exportWorkbookWriter.contentType(),
                exportWorkbookWriter.write(rows, exportedAt));
    }

    private void validateRequest(CreateGroupCreationMarketingTaskDTO request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "建群营销任务不能为空");
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
        if (request.sendIntervalSeconds() != null && request.sendIntervalSeconds() < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "发送间隔必须大于 0 秒");
        }
        if (request.materials() == null || request.materials().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "请上传料子文件");
        }
    }

    private static List<Long> normalizeExportIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择要导出的建群营销任务");
        }
        List<Long> normalized = ids.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择要导出的建群营销任务");
        }
        return normalized;
    }

    private MarketingTemplate requireTemplate(Long id) {
        MarketingTemplate template = templateMapper.selectById(id);
        if (template == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "营销模板不存在: " + id);
        }
        return template;
    }

    private static List<ValidMaterial> validMaterials(List<GroupCreationMarketingMaterialDTO> materials) {
        List<ValidMaterial> valid = new ArrayList<>();
        for (int i = 0; i < materials.size(); i++) {
            GroupCreationMarketingMaterialDTO material = materials.get(i);
            if (material == null) {
                continue;
            }
            List<String> phones = materialPhones(material.content());
            if (phones.isEmpty()) {
                continue;
            }
            String fileName = StringUtils.hasText(material.fileName()) ? material.fileName().trim() : "material-" + (i + 1) + ".txt";
            valid.add(new ValidMaterial(i, fileName, phones));
        }
        return valid;
    }

    private GroupCreationMarketingTask buildTask(CreateGroupCreationMarketingTaskDTO request,
                                                 MarketingTemplate template,
                                                 int matchedCount,
                                                 int unmatchedFileCount,
                                                 long now) {
        GroupCreationMarketingTask task = new GroupCreationMarketingTask();
        task.setTaskName(request.taskName().trim());
        task.setAccountGroupId(request.accountGroupId());
        task.setAccountGroupName(snapshotName(request.accountGroupName(), "账号分组-" + request.accountGroupId()));
        task.setMarketingTemplateId(template.getId());
        task.setMarketingTemplateName(template.getTemplateName());
        task.setStatus(GroupCreationMarketingTaskStatus.PENDING.code());
        task.setMatchedItemCount(matchedCount);
        task.setUnmatchedFileCount(unmatchedFileCount);
        task.setSuccessCount(0);
        task.setFailedCount(0);
        task.setAbandonedCount(0);
        task.setSendIntervalSeconds(normalizeSendIntervalSeconds(request.sendIntervalSeconds()));
        task.setGroupNamePrefix(trimToNull(request.groupNamePrefix()));
        task.setRemark(trimToNull(request.remark()));
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    private List<GroupCreationMarketingItem> buildItems(Long taskId,
                                                        List<GroupCreationMarketingAccountCandidate> accounts,
                                                        List<ValidMaterial> materials,
                                                        int matchedCount,
                                                        CreateGroupCreationMarketingTaskDTO request,
                                                        long now) {
        List<GroupCreationMarketingItem> items = new ArrayList<>();
        for (int i = 0; i < matchedCount; i++) {
            GroupCreationMarketingAccountCandidate account = accounts.get(i);
            ValidMaterial material = materials.get(i);
            GroupCreationMarketingItem item = new GroupCreationMarketingItem();
            item.setTaskId(taskId);
            item.setFileIndex(material.fileIndex());
            item.setFileName(material.fileName());
            item.setMaterialContent(String.join("\n", material.phones()));
            item.setParticipantCount(material.phones().size());
            item.setAccountId(account.getAccountId());
            item.setAccountPhone(account.getAccountPhone());
            item.setProtocolAccountId(account.getProtocolAccountId());
            item.setGroupSubject(groupSubject(request.groupNamePrefix(), request.taskName(), i));
            item.setStatus(GroupCreationMarketingItemStatus.PENDING.code());
            item.setNextRunAt(0L);
            item.setCreatedAt(now);
            item.setUpdatedAt(now);
            items.add(item);
        }
        return items;
    }

    private static List<String> materialPhones(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        return content.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .map(GroupCreationMarketingTaskServiceImpl::firstPhoneToken)
                .map(GroupCreationMarketingTaskServiceImpl::normalizePhone)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private static String firstPhoneToken(String line) {
        return line.split("[,，]+")[0].trim();
    }

    private static String normalizePhone(String value) {
        try {
            String jid = WhatsappJids.userJid(value);
            int at = jid.indexOf('@');
            String phone = at > 0 ? jid.substring(0, at) : jid;
            if (phone.length() < 7 || phone.length() > 15 || !phone.chars().allMatch(Character::isDigit)) {
                return null;
            }
            return phone;
        } catch (ProtocolException ex) {
            return null;
        }
    }

    private static int normalizeSendIntervalSeconds(Integer value) {
        return value == null || value < 1 ? 30 : value;
    }

    private static List<GroupCreationMarketingAccountCandidate> usableAccounts(List<GroupCreationMarketingAccountCandidate> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return List.of();
        }
        return accounts.stream()
                .filter(GroupCreationMarketingTaskServiceImpl::usableAccount)
                .toList();
    }

    private static boolean usableAccount(GroupCreationMarketingAccountCandidate account) {
        return account != null
                && StringUtils.hasText(account.getProtocolAccountId())
                && Integer.valueOf(AccountStateCode.NORMAL).equals(account.getAccountState())
                && Integer.valueOf(AccountLoginStateCode.ONLINE).equals(account.getLoginState())
                && (account.getRiskStatus() == null || account.getRiskStatus() <= 1)
                && account.getMuteStatus() == null;
    }

    private static String groupSubject(String prefix, String taskName, int index) {
        String base = StringUtils.hasText(prefix) ? prefix.trim() : taskName.trim();
        String subject = base + "-" + (index + 1);
        return subject.length() <= 100 ? subject : subject.substring(0, 100);
    }

    private static String snapshotName(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static GroupCreationMarketingTaskVO toVO(GroupCreationMarketingTask task) {
        return new GroupCreationMarketingTaskVO(task.getId(), task.getTaskName(), task.getAccountGroupId(),
                task.getAccountGroupName(), task.getMarketingTemplateId(), task.getMarketingTemplateName(),
                task.getMarketingTaskId(), task.getStatus(), task.getMatchedItemCount(), task.getUnmatchedFileCount(),
                task.getSuccessCount(), task.getFailedCount(), task.getAbandonedCount(), task.getSendIntervalSeconds(), task.getGroupNamePrefix(),
                task.getRemark(), task.getFinishedAt(), task.getCreatedAt(), task.getUpdatedAt());
    }

    private static GroupCreationMarketingItemVO toItemVO(GroupCreationMarketingItem item) {
        return new GroupCreationMarketingItemVO(item.getId(), item.getFileIndex(), item.getFileName(),
                item.getParticipantCount(), item.getAccountId(), item.getAccountPhone(), item.getProtocolAccountId(),
                item.getGroupSubject(), item.getGroupJid(), item.getGroupLinkId(), item.getMarketingTaskId(),
                item.getMarketingTargetId(), item.getMarketingAttemptId(), item.getCommandId(), item.getStatus(),
                item.getReasonCode(), item.getReasonMessage(), item.getStartedAt(), item.getFinishedAt(),
                item.getCreatedAt(), item.getUpdatedAt());
    }

    private static GroupCreationMarketingTaskDetailVO toDetailVO(GroupCreationMarketingTask task,
                                                                 List<GroupCreationMarketingItemVO> items) {
        return new GroupCreationMarketingTaskDetailVO(task.getId(), task.getTaskName(), task.getAccountGroupId(),
                task.getAccountGroupName(), task.getMarketingTemplateId(), task.getMarketingTemplateName(),
                task.getMarketingTaskId(), task.getStatus(), task.getMatchedItemCount(), task.getUnmatchedFileCount(),
                task.getSuccessCount(), task.getFailedCount(), task.getAbandonedCount(), task.getSendIntervalSeconds(), task.getGroupNamePrefix(),
                task.getRemark(), task.getFinishedAt(), task.getCreatedAt(), task.getUpdatedAt(), items);
    }

    private record ValidMaterial(int fileIndex, String fileName, List<String> phones) {
    }
}
