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
import com.armada.marketing.model.vo.GroupCreationMarketingItemVO;
import com.armada.marketing.model.vo.GroupCreationMarketingTaskDetailVO;
import com.armada.marketing.model.vo.GroupCreationMarketingTaskVO;
import com.armada.marketing.service.GroupCreationMarketingTaskService;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class GroupCreationMarketingTaskServiceImpl implements GroupCreationMarketingTaskService {

    private final GroupCreationMarketingTaskMapper mapper;
    private final MarketingTemplateMapper templateMapper;
    private final MarketingTaskMapper marketingTaskMapper;

    public GroupCreationMarketingTaskServiceImpl(GroupCreationMarketingTaskMapper mapper,
                                                 MarketingTemplateMapper templateMapper,
                                                 MarketingTaskMapper marketingTaskMapper) {
        this.mapper = mapper;
        this.templateMapper = templateMapper;
        this.marketingTaskMapper = marketingTaskMapper;
    }

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

    @Override
    public PageResult<GroupCreationMarketingTaskVO> listTasks(GroupCreationMarketingTaskQuery query) {
        GroupCreationMarketingTaskQuery normalized = query == null ? new GroupCreationMarketingTaskQuery() : query;
        long total = mapper.countPage(normalized);
        List<GroupCreationMarketingTaskVO> rows = total == 0
                ? List.of()
                : mapper.selectPage(normalized).stream().map(GroupCreationMarketingTaskServiceImpl::toVO).toList();
        return PageResult.of(rows, normalized.getPage(), normalized.getPageSize(), total);
    }

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

    @Override
    public List<GroupCreationMarketingAccountCandidate> accountCandidates(Long accountGroupId) {
        if (accountGroupId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择账号分组");
        }
        return usableAccounts(mapper.selectAccountCandidatesByGroupId(accountGroupId));
    }

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
