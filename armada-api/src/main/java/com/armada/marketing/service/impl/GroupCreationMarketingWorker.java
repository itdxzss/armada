package com.armada.marketing.service.impl;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.service.AccountRestrictionService;
import com.armada.marketing.mapper.GroupCreationMarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.armada.marketing.model.entity.GroupCreationMarketingTask;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.marketing.model.enums.GroupCreationMarketingItemStatus;
import com.armada.marketing.model.support.GroupCreateRestrictionClassifier;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.platform.protocol.model.command.ProtocolMarketingMessageCommandRequest;
import com.armada.platform.protocol.model.result.GroupCreateParticipantResult;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Component
public class GroupCreationMarketingWorker {

    private static final Logger log = LoggerFactory.getLogger(GroupCreationMarketingWorker.class);

    private static final String SOURCE_GROUP_CREATION_MARKETING = "group_creation_marketing";
    private static final String REASON_ACCOUNT_OFFLINE = "ACCOUNT_OFFLINE";
    private static final String REASON_ACCOUNT_UNUSABLE = "ACCOUNT_UNUSABLE";
    private static final String REASON_GROUP_CREATE_FAILED = "GROUP_CREATE_FAILED";
    private static final int MAX_PROCESS_CONCURRENCY = 5;
    private static final int MAX_CONTACT_PRE_SAVE_CONCURRENCY = 10;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private static final AtomicInteger CONTACT_PRE_SAVE_THREAD_SEQUENCE = new AtomicInteger();
    private static final ExecutorService CONTACT_PRE_SAVE_EXECUTOR = Executors.newFixedThreadPool(
            MAX_CONTACT_PRE_SAVE_CONCURRENCY,
            runnable -> {
                Thread thread = new Thread(runnable,
                        "group-creation-marketing-contact-pre-save-"
                                + CONTACT_PRE_SAVE_THREAD_SEQUENCE.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });

    private final GroupCreationMarketingTaskMapper groupCreationMapper;
    private final MarketingTemplateMapper templateMapper;
    private final MarketingTemplateFileMapper fileMapper;
    private final MarketingMessageComposer messageComposer;
    private final ProtocolCommandOutboxService outboxService;
    private final ContactPort contactPort;
    private final GroupCreatePort groupCreatePort;
    private final GroupCreationMarketingRetryService retryService;
    private final AccountRestrictionService accountRestrictionService;
    private final ObjectMapper objectMapper;
    private final TransactionOperations transactionOperations;

    public GroupCreationMarketingWorker(GroupCreationMarketingTaskMapper groupCreationMapper,
                                        MarketingTemplateMapper templateMapper,
                                        MarketingTemplateFileMapper fileMapper,
                                        MarketingMessageComposer messageComposer,
                                        ProtocolCommandOutboxService outboxService,
                                        ContactPort contactPort,
                                        GroupCreatePort groupCreatePort,
                                        GroupCreationMarketingRetryService retryService,
                                        AccountRestrictionService accountRestrictionService,
                                        ObjectMapper objectMapper,
                                        PlatformTransactionManager transactionManager) {
        this.groupCreationMapper = groupCreationMapper;
        this.templateMapper = templateMapper;
        this.fileMapper = fileMapper;
        this.messageComposer = messageComposer;
        this.outboxService = outboxService;
        this.contactPort = contactPort;
        this.groupCreatePort = groupCreatePort;
        this.retryService = retryService;
        this.accountRestrictionService = accountRestrictionService;
        this.objectMapper = objectMapper;
        this.transactionOperations = new TransactionTemplate(transactionManager);
    }

    public void processDueItems(int limit) {
        int normalizedLimit = Math.max(1, limit);
        long now = System.currentTimeMillis();
        List<GroupCreationMarketingItem> items = groupCreationMapper.selectDueItems(normalizedLimit, now);
        if (items.isEmpty()) {
            return;
        }
        if (items.size() == 1) {
            processOne(items.get(0));
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(MAX_PROCESS_CONCURRENCY, items.size()),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "group-creation-marketing-worker-" + THREAD_SEQUENCE.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            List<Future<?>> futures = new ArrayList<>(items.size());
            for (GroupCreationMarketingItem item : items) {
                futures.add(executor.submit(() -> processOne(item)));
            }
            for (Future<?> future : futures) {
                await(future);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void await(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("建群营销并发执行被中断", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("建群营销并发执行失败", cause);
        }
    }

    public void processOne(GroupCreationMarketingItem item) {
        Long previousTenant = TenantContext.get();
        if (item.getTenantId() != null) {
            TenantContext.set(item.getTenantId());
        }
        try {
            doProcessOne(item);
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    private void doProcessOne(GroupCreationMarketingItem item) {
        long now = System.currentTimeMillis();
        ClaimedItemContext context = transactionOperations.execute(status -> claimItemForProcessing(item, now));
        if (context == null) {
            return;
        }
        GroupCreationMarketingTask task = context.task();
        GroupCreationMarketingAccountCandidate account = context.account();

        List<String> participants = participants(item.getMaterialContent());
        ContactSaveSummary contactSaveSummary = preSaveContacts(account.getProtocolAccountId(), participants);
        GroupCreateResult groupResult;
        try {
            groupResult = groupCreatePort.create(account.getProtocolAccountId(), item.getGroupSubject(), participants, true);
        } catch (RuntimeException ex) {
            String reason = readableMessage(ex);
            Optional<String> restrictedReason = GroupCreateRestrictionClassifier.restrictedReason(ex);
            long failedAt = System.currentTimeMillis();
            transactionOperations.executeWithoutResult(status -> {
                restrictedReason.ifPresent(value -> accountRestrictionService.markGroupCreateRestricted(
                        account.getAccountId(),
                        account.getProtocolAccountId(),
                        value,
                        failedAt));
                retryService.resetItemForAccountRetry(
                        item, task, GroupCreationMarketingRetryService.STAGE_GROUP_CREATE,
                        REASON_GROUP_CREATE_FAILED, reason, failedAt);
            });
            return;
        }
        if (groupResult == null || !StringUtils.hasText(groupResult.groupJid())) {
            String reason = "协议未返回群JID";
            transactionOperations.executeWithoutResult(status -> retryService.resetItemForAccountRetry(
                    item, task, GroupCreationMarketingRetryService.STAGE_GROUP_CREATE,
                    REASON_GROUP_CREATE_FAILED, reason, System.currentTimeMillis()));
            return;
        }

        MarketingTemplate template = requireTemplate(task.getMarketingTemplateId());
        MarketingTemplateFile imageFile = template.getImageFileId() == null ? null : fileMapper.selectById(template.getImageFileId());
        MarketingMessageComposer.ComposedMessage message = messageComposer.compose(template, imageFile);
        String protocolResultJson = protocolResultJson(contactSaveSummary, groupResult);
        String commandId = newCommandId();
        transactionOperations.executeWithoutResult(status -> {
            enqueueMarketingCommand(task.getTenantId(), task.getId(), item, groupResult.groupJid(), commandId, message);
            int marked = groupCreationMapper.markItemMarketingSending(
                    item.getId(),
                    groupResult.groupJid(),
                    null,
                    null,
                    null,
                    null,
                    commandId,
                    protocolResultJson,
                    System.currentTimeMillis());
            if (marked == 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "建群营销执行项状态已变化: " + item.getId());
            }
        });
    }

    private ClaimedItemContext claimItemForProcessing(GroupCreationMarketingItem item, long now) {
        int claimed = groupCreationMapper.claimItem(
                item.getId(),
                GroupCreationMarketingItemStatus.PENDING.code(),
                GroupCreationMarketingItemStatus.GROUP_CREATING.code(),
                now);
        if (claimed == 0) {
            return null;
        }
        GroupCreationMarketingTask task = requireTask(item.getTaskId());
        GroupCreationMarketingAccountCandidate account =
                groupCreationMapper.selectAccountCandidateByAccountId(item.getAccountId());
        account = resolveExecutableAccount(item, task, account, now);
        return account == null ? null : new ClaimedItemContext(task, account);
    }

    private GroupCreationMarketingAccountCandidate resolveExecutableAccount(GroupCreationMarketingItem item,
                                                                            GroupCreationMarketingTask task,
                                                                            GroupCreationMarketingAccountCandidate account,
                                                                            long now) {
        if (!unusable(account) && online(account)) {
            return account;
        }
        String reasonCode = unusable(account) ? REASON_ACCOUNT_UNUSABLE : REASON_ACCOUNT_OFFLINE;
        String reasonMessage = unusable(account) ? "账号不可用" : "账号离线";
        GroupCreationMarketingAccountCandidate replacement = retryService.replaceClaimedItemAccountForRetry(
                item,
                task,
                GroupCreationMarketingRetryService.STAGE_ACCOUNT_CHECK,
                reasonCode,
                reasonMessage,
                now);
        if (replacement == null) {
            return null;
        }
        log.info("建群营销执行账号已替换 itemId={} oldAccountId={} newAccountId={} newProtocolAccountId={}",
                item.getId(), account == null ? null : account.getAccountId(),
                replacement.getAccountId(), replacement.getProtocolAccountId());
        return replacement;
    }

    private ContactSaveSummary preSaveContacts(String protocolAccountId, List<String> participants) {
        int submitted = 0;
        List<ContactSaveFailure> failures = new ArrayList<>();
        for (String participant : participants) {
            try {
                submitContactPreSave(protocolAccountId, participant);
                submitted++;
            } catch (RuntimeException ex) {
                String reason = readableMessage(ex);
                failures.add(new ContactSaveFailure(participant, reason));
                log.warn("建群营销联系人预保存提交失败 protocolAccountId={} participant={} reason={}",
                        protocolAccountId, participant, reason);
            }
        }
        return new ContactSaveSummary(participants.size(), submitted, failures.size(), failures.stream().limit(5).toList());
    }

    private void submitContactPreSave(String protocolAccountId, String participant) {
        CONTACT_PRE_SAVE_EXECUTOR.execute(() -> {
            try {
                contactPort.saveContact(protocolAccountId, participant, participant);
            } catch (RuntimeException ex) {
                log.warn("建群营销联系人预保存异步失败 protocolAccountId={} participant={} reason={}",
                        protocolAccountId, participant, readableMessage(ex));
            }
        });
    }

    private String protocolResultJson(ContactSaveSummary contactSaveSummary, GroupCreateResult groupResult) {
        return protocolResultJson(new GroupCreationProtocolResult(
                contactSaveSummary,
                new GroupCreateProtocolResult(
                        groupResult.partial(),
                        groupResult.results() == null ? List.of() : groupResult.results(),
                        null)));
    }

    private String protocolFailureJson(ContactSaveSummary contactSaveSummary, String reason) {
        return protocolResultJson(new GroupCreationProtocolResult(
                contactSaveSummary,
                new GroupCreateProtocolResult(null, List.of(), reason)));
    }

    private String protocolResultJson(GroupCreationProtocolResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("建群营销协议结果摘要序列化失败", ex);
        }
    }

    private void enqueueMarketingCommand(Long tenantId,
                                         Long taskId,
                                         GroupCreationMarketingItem item,
                                         String groupJid,
                                         String commandId,
                                         MarketingMessageComposer.ComposedMessage message) {
        String imageBase64 = message.imageBytes() == null ? null : Base64.getEncoder().encodeToString(message.imageBytes());
        outboxService.enqueueMarketingMessageCommands(List.of(new ProtocolMarketingMessageCommandRequest(
                tenantId,
                null,
                null,
                null,
                null,
                item.getAccountId(),
                item.getProtocolAccountId(),
                groupJid,
                message.messageType(),
                message.text(),
                imageBase64,
                message.imageMimetype(),
                linkCardPayload(message.linkCard()),
                buttonCardPayload(message.buttonCard()),
                SOURCE_GROUP_CREATION_MARKETING,
                commandId,
                taskId,
                item.getId())));
    }

    private GroupCreationMarketingTask requireTask(Long taskId) {
        GroupCreationMarketingTask task = groupCreationMapper.selectTaskById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "建群营销任务不存在: " + taskId);
        }
        return task;
    }

    private MarketingTemplate requireTemplate(Long templateId) {
        MarketingTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "营销模板不存在: " + templateId);
        }
        return template;
    }

    private static boolean unusable(GroupCreationMarketingAccountCandidate account) {
        return account == null
                || !StringUtils.hasText(account.getProtocolAccountId())
                || !Integer.valueOf(AccountStateCode.NORMAL).equals(account.getAccountState())
                || (account.getRiskStatus() != null && account.getRiskStatus() > 1)
                || account.getMuteStatus() != null;
    }

    private static boolean online(GroupCreationMarketingAccountCandidate account) {
        return account != null && Integer.valueOf(AccountLoginStateCode.ONLINE).equals(account.getLoginState());
    }

    private static List<String> participants(String materialContent) {
        if (!StringUtils.hasText(materialContent)) {
            return List.of();
        }
        return materialContent.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static String readableMessage(RuntimeException ex) {
        return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    private static String newCommandId() {
        return "cmd_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static ProtocolMarketingMessageCommandRequest.MarketingLinkCardPayload linkCardPayload(
            MarketingMessageComposer.LinkCardPayload linkCard) {
        if (linkCard == null) {
            return null;
        }
        return new ProtocolMarketingMessageCommandRequest.MarketingLinkCardPayload(
                linkCard.url(),
                linkCard.title(),
                linkCard.description(),
                mediaPayload(linkCard.thumbnail()));
    }

    private static ProtocolMarketingMessageCommandRequest.MarketingButtonCardPayload buttonCardPayload(
            MarketingMessageComposer.ButtonCardPayload buttonCard) {
        if (buttonCard == null) {
            return null;
        }
        return new ProtocolMarketingMessageCommandRequest.MarketingButtonCardPayload(
                buttonCard.title(),
                buttonCard.footer(),
                buttonCard.buttons().stream()
                        .map(button -> new ProtocolMarketingMessageCommandRequest.MarketingButtonPayload(
                                button.type(), button.displayText(), button.value()))
                        .toList(),
                mediaPayload(buttonCard.thumbnail()));
    }

    private static ProtocolMarketingMessageCommandRequest.MarketingMediaPayload mediaPayload(
            MarketingMessageComposer.MediaPayload media) {
        if (media == null || media.bytes() == null || media.bytes().length == 0) {
            return null;
        }
        return new ProtocolMarketingMessageCommandRequest.MarketingMediaPayload(
                Base64.getEncoder().encodeToString(media.bytes()),
                media.mimetype());
    }

    private record ContactSaveSummary(int total, int success, int failed, List<ContactSaveFailure> failures) {
    }

    private record ContactSaveFailure(String participant, String reason) {
    }

    private record GroupCreationProtocolResult(ContactSaveSummary contactSave,
                                               GroupCreateProtocolResult groupCreate) {
    }

    private record GroupCreateProtocolResult(Boolean partial,
                                             List<GroupCreateParticipantResult> results,
                                             String failureReason) {
    }

    private record ClaimedItemContext(GroupCreationMarketingTask task,
                                      GroupCreationMarketingAccountCandidate account) {
    }
}
