package com.armada.platform.protocol.service.impl;

import com.armada.platform.kafka.config.NormalGroupCreationKafkaProperties;
import com.armada.platform.kafka.config.ProtocolAccountCommandProperties;
import com.armada.platform.kafka.config.ProtocolAndroidCommandProperties;
import com.armada.platform.kafka.config.ProtocolMasterCommandProperties;
import com.armada.platform.kafka.dispatch.ProtocolCommandDispatchTrigger;
import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountGroupSyncCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolGroupHealthCheckCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolGroupJoinCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolGroupSnapshotCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolMessageOutboxCommand;
import com.armada.platform.protocol.model.command.ProtocolOfflineCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolOnlineCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolNormalGroupCreationCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupJoinCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskContactSaveCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskCreatorLeaveCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskMaterialAdminCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupSettingsCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskManagerAdminCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskMemberQueryCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskPullerInviteCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskBatchAddCommandRequest;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.port.MessageCommandRecoveryPort;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.trace.TraceContext;
import com.armada.shared.trace.TraceIds;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 协议命令 Outbox 应用服务实现。
 *
 * <p>本服务只在本地事务中写 outbox。事务提交后通过 trigger 异步唤醒 dispatcher,
 * Kafka 发送不包在本事务内。</p>
 */
@Service
public class ProtocolCommandOutboxServiceImpl
        implements ProtocolCommandOutboxService, MessageCommandRecoveryPort {

    private static final Logger log = LoggerFactory.getLogger(ProtocolCommandOutboxServiceImpl.class);

    /** 账号上线命令类型。 */
    public static final String COMMAND_TYPE_ACCOUNT_ONLINE_REQUESTED = "account.online.requested";

    /** 账号下线命令类型。 */
    public static final String COMMAND_TYPE_ACCOUNT_OFFLINE_REQUESTED = "account.offline.requested";

    /** 群链接健康检查命令类型。 */
    public static final String COMMAND_TYPE_GROUP_HEALTH_CHECK_REQUESTED = "group.health_check.requested";

    /** 账号当前群同步命令类型。 */
    public static final String COMMAND_TYPE_ACCOUNT_GROUPS_SYNC_REQUESTED = "account.groups_sync.requested";

    /** 批量进群命令类型。 */
    public static final String COMMAND_TYPE_GROUP_JOIN_REQUESTED = "group.join.requested";

    /** 按需单群快照命令类型。 */
    public static final String COMMAND_TYPE_GROUP_SNAPSHOT_REQUESTED = "group.snapshot.requested";

    /** 联系人保存命令类型。 */
    public static final String COMMAND_TYPE_CONTACT_SAVE_REQUESTED = "contact.save.requested";

    /** 群成员变更命令类型。 */
    public static final String COMMAND_TYPE_GROUP_PARTICIPANTS_REQUESTED = "group.participants.requested";

    /** 建群者退出群组命令类型。 */
    public static final String COMMAND_TYPE_GROUP_LEAVE_REQUESTED = "group.leave.requested";

    /** 新建普群通用动作命令类型。 */
    public static final String COMMAND_TYPE_NORMAL_GROUP_CREATION_REQUESTED =
            "group.normal_creation.requested";

    /** 群成员事实查询命令类型。 */
    /** 拉群任务管理员应用单项群设置的命令类型。 */
    public static final String COMMAND_TYPE_GROUP_SETTINGS_REQUESTED = "group.settings.requested";

    public static final String COMMAND_TYPE_GROUP_MEMBERS_QUERY_REQUESTED =
            "group.members.query.requested";

    /** 营销消息发送命令类型。 */
    public static final String COMMAND_TYPE_MESSAGE_SEND_REQUESTED = "message.send.requested";
    public static final String COMMAND_TYPE_STATUS_PUBLISH_REQUESTED = "status.publish.requested";

    /** 账号聚合类型。 */
    public static final String AGGREGATE_TYPE_ACCOUNT = "ACCOUNT";

    /** 群链接聚合类型。 */
    public static final String AGGREGATE_TYPE_GROUP_LINK = "GROUP_LINK";

    /** 进群任务明细聚合类型。 */
    public static final String AGGREGATE_TYPE_JOIN_TASK_RESULT = "JOIN_TASK_RESULT";

    /** 单群资料同步任务聚合类型。 */
    public static final String AGGREGATE_TYPE_GROUP_METADATA_SYNC_TASK =
            "GROUP_METADATA_SYNC_TASK";

    /** 人工批量群任务明细聚合类型。 */
    public static final String AGGREGATE_TYPE_GROUP_BATCH_TASK_ITEM =
            "GROUP_BATCH_TASK_ITEM";

    /** 普通拉群账号动作聚合类型。 */
    public static final String AGGREGATE_TYPE_PULL_TASK_ACCOUNT_ACTION = "PULL_TASK_ACCOUNT_ACTION";

    /** 普通拉群单次拉人调用聚合类型。 */
    public static final String AGGREGATE_TYPE_PULL_TASK_PULL_CALL = "PULL_TASK_PULL_CALL";

    /** 普通拉群料子成员聚合类型。 */
    public static final String AGGREGATE_TYPE_PULL_TASK_MATERIAL_MEMBER =
            "PULL_TASK_MATERIAL_MEMBER";

    /** 普通拉群成员查询聚合类型。 */
    public static final String AGGREGATE_TYPE_PULL_TASK_MEMBER_QUERY =
            "PULL_TASK_MEMBER_QUERY";

    /** 新建普群计划群聚合类型。 */
    public static final String AGGREGATE_TYPE_NORMAL_GROUP_CREATION_ITEM =
            "NORMAL_GROUP_CREATION_ITEM";

    /** 营销发送尝试聚合类型。 */
    public static final String AGGREGATE_TYPE_MARKETING_SEND_ATTEMPT = "MARKETING_SEND_ATTEMPT";

    /** 建群营销执行项聚合类型。 */
    public static final String AGGREGATE_TYPE_GROUP_CREATION_MARKETING_ITEM = "GROUP_CREATION_MARKETING_ITEM";

    /** 历史群拉人营销成员聚合类型。 */
    public static final String AGGREGATE_TYPE_HISTORICAL_GROUP_PULL_MEMBER = "HISTORICAL_GROUP_PULL_MEMBER";

    /** 超链任务唯一 recipient 聚合类型。 */
    public static final String AGGREGATE_TYPE_HYPERLINK_TASK_RECIPIENT = "HYPERLINK_TASK_RECIPIENT";

    public static final String AGGREGATE_TYPE_CONTACT_TASK_RECIPIENT = "CONTACT_TASK_RECIPIENT";

    public static final String AGGREGATE_TYPE_FEED_TASK_ACCOUNT = "FEED_TASK_ACCOUNT";

    private static final int MAX_ACCOUNT_LIFECYCLE_COMMANDS_PER_BATCH = 1_000;
    private static final int MAX_COMMANDS_PER_BATCH = 500;
    private static final long IMMEDIATE_RETRY_AT = 0L;
    private static final String COMMAND_ID_PREFIX = "cmd_";
    private static final String BATCH_ID_PREFIX = "batch_";
    private static final String SOURCE_HISTORICAL_GROUP_PULL = "historical_group_pull";
    private static final String SOURCE_HYPERLINK_TASK = "hyperlink_task";
    private static final String SOURCE_CONTACT_TASK = "contact_task";
    private static final String SOURCE_FEED_TASK = "feed_task";

    private final ProtocolCommandOutboxMapper mapper;
    private final ObjectMapper objectMapper;
    private final ProtocolCommandDispatchTrigger dispatchTrigger;
    private final ProtocolAccountCommandProperties accountCommandProperties;
    private final ProtocolMasterCommandProperties masterCommandProperties;
    private final ProtocolAndroidCommandProperties androidCommandProperties;
    private final NormalGroupCreationKafkaProperties normalGroupCreationKafkaProperties;

    /**
     * 创建协议命令 Outbox service。
     *
     * @param mapper          outbox mapper
     * @param objectMapper    JSON 序列化器
     * @param dispatchTrigger outbox 提交后 dispatch 触发器
     * @param accountCommandProperties 账号上线命令 Kafka topic 配置
     * @param masterCommandProperties  master 路由命令 Kafka topic 配置
     * @param androidCommandProperties Android 协议命令 Kafka topic 配置
     * @param normalGroupCreationKafkaProperties 新建普群独立 Kafka topic 配置
     */
    public ProtocolCommandOutboxServiceImpl(ProtocolCommandOutboxMapper mapper,
                                            ObjectMapper objectMapper,
                                            ProtocolCommandDispatchTrigger dispatchTrigger,
                                            ProtocolAccountCommandProperties accountCommandProperties,
                                            ProtocolMasterCommandProperties masterCommandProperties,
                                            ProtocolAndroidCommandProperties androidCommandProperties,
                                            NormalGroupCreationKafkaProperties
                                                    normalGroupCreationKafkaProperties) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.dispatchTrigger = dispatchTrigger;
        this.accountCommandProperties = accountCommandProperties;
        this.masterCommandProperties = masterCommandProperties;
        this.androidCommandProperties = androidCommandProperties;
        this.normalGroupCreationKafkaProperties = normalGroupCreationKafkaProperties;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean replay(long tenantId, String commandId, long now) {
        if (tenantId <= 0 || commandId == null || commandId.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "消息恢复缺少租户或 commandId");
        }
        return mapper.replayMessageCommand(tenantId, commandId,
                COMMAND_TYPE_MESSAGE_SEND_REQUESTED,
                List.of(ProtocolCommandOutboxStatus.SENT.code(),
                        ProtocolCommandOutboxStatus.DEAD.code()),
                ProtocolCommandOutboxStatus.PENDING.code(), now) == 1;
    }

    /**
     * 批量写入账号上线 outbox 命令。
     *
     * <p>单条命令不生成 batch_id;多条命令共享一个 batch_id,便于批量上线排查。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueueOnlineCommands(List<ProtocolOnlineCommandRequest> commands) {
        validateOnlineCommands(commands);

        String batchId = commands.size() == 1 ? null : newBatchId();
        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());

        for (ProtocolOnlineCommandRequest command : commands) {
            String commandId = newCommandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toOnlineOutboxRow(command, commandId, batchId, now));
        }

        return insertPendingRows(batchId, commandIds, rows);
    }

    /**
     * 批量写入账号下线 outbox 命令。
     *
     * <p>单条命令不生成 batch_id;多条命令共享一个 batch_id,便于批量下线排查。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueueOfflineCommands(List<ProtocolOfflineCommandRequest> commands) {
        validateOfflineCommands(commands);

        String batchId = commands.size() == 1 ? null : newBatchId();
        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());

        for (ProtocolOfflineCommandRequest command : commands) {
            String commandId = newCommandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toOfflineOutboxRow(command, commandId, batchId, now));
        }

        return insertPendingRows(batchId, commandIds, rows);
    }

    /**
     * 取消显式下线账号尚未发布的旧上线命令。
     */
    @Override
    public int cancelPendingAccountOnlineCommands(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return 0;
        }
        if (accountIds.size() > MAX_ACCOUNT_LIFECYCLE_COMMANDS_PER_BATCH
                || accountIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "待取消的账号上线命令 ID 非法或超过 " + MAX_ACCOUNT_LIFECYCLE_COMMANDS_PER_BATCH + " 条");
        }
        return mapper.cancelPendingAccountOnlineCommandsInternal(
                accountIds,
                AGGREGATE_TYPE_ACCOUNT,
                COMMAND_TYPE_ACCOUNT_ONLINE_REQUESTED,
                ProtocolCommandOutboxStatus.PENDING.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(),
                System.currentTimeMillis());
    }

    /** 结束普通拉群任务或单群时阻止尚未发布的真实协议副作用。 */
    @Override
    public int cancelPendingPullTaskCommands(long taskId, Long executionId, long now) {
        if (taskId <= 0 || executionId != null && executionId <= 0 || now <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "待取消的普通拉群命令范围非法");
        }
        return mapper.cancelPendingPullTaskCommandsInternal(
                taskId,
                executionId,
                AGGREGATE_TYPE_PULL_TASK_ACCOUNT_ACTION,
                AGGREGATE_TYPE_PULL_TASK_PULL_CALL,
                AGGREGATE_TYPE_PULL_TASK_MATERIAL_MEMBER,
                AGGREGATE_TYPE_PULL_TASK_MEMBER_QUERY,
                List.of(
                        ProtocolCommandOutboxStatus.PENDING.code(),
                        ProtocolCommandOutboxStatus.LOCKED.code()),
                ProtocolCommandOutboxStatus.DISPATCHING.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(),
                ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code(),
                "PULL_TASK_ENDED",
                now);
    }

    /**
     * 批量写入群链接健康检查 outbox 命令。
     *
     * <p>单条命令不生成 batch_id;多条命令共享一个 batch_id,便于单轮巡检排查。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueueGroupHealthCheckCommands(
            List<ProtocolGroupHealthCheckCommandRequest> commands) {
        validateGroupHealthCheckCommands(commands);

        String batchId = commands.size() == 1 ? null : newBatchId();
        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());

        for (ProtocolGroupHealthCheckCommandRequest command : commands) {
            String commandId = newCommandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toGroupHealthCheckOutboxRow(command, commandId, batchId, now));
        }

        return insertPendingRows(batchId, commandIds, rows);
    }

    /**
     * 批量写入账号当前群同步 outbox 命令。
     *
     * <p>单条命令不生成 batch_id;多条命令共享一个 batch_id,便于单轮账号群刷新排查。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueueAccountGroupSyncCommands(
            List<ProtocolAccountGroupSyncCommandRequest> commands) {
        validateAccountGroupSyncCommands(commands);

        String batchId = commands.size() == 1 ? null : newBatchId();
        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());

        for (ProtocolAccountGroupSyncCommandRequest command : commands) {
            String commandId = newCommandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toAccountGroupSyncOutboxRow(command, commandId, batchId, now));
        }

        return insertPendingRows(batchId, commandIds, rows);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueueGroupSnapshotCommands(
            List<ProtocolGroupSnapshotCommandRequest> commands) {
        validateGroupSnapshotCommands(commands);
        String batchId = commands.size() == 1 ? null : newBatchId();
        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        for (ProtocolGroupSnapshotCommandRequest command : commands) {
            String commandId = newCommandId();
            commandIds.add(commandId);
            rows.add(toGroupSnapshotOutboxRow(command, commandId, batchId, now));
        }
        return insertPendingRows(batchId, commandIds, rows);
    }

    /**
     * {@inheritDoc}
     *
     * <p>每行 outbox 使用进群明细作为聚合 ID，并保存稳定的 {@code join-task:{taskId}} batchId。
     * 命令列表可同时包含多个任务；只有全部命令属于同一任务时，返回值才携带公共 batchId。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueueGroupJoinCommands(
            List<ProtocolGroupJoinCommandRequest> commands) {
        validateGroupJoinCommands(commands);

        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());
        for (ProtocolGroupJoinCommandRequest command : commands) {
            String commandId = newCommandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toGroupJoinOutboxRow(command, commandId, now));
        }
        Long firstTaskId = commands.get(0).joinTaskId();
        String commonBatchId = commands.stream().allMatch(command -> firstTaskId.equals(command.joinTaskId()))
                ? joinTaskBatchId(firstTaskId) : null;
        return insertPendingRows(commonBatchId, commandIds, rows);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueuePullTaskGroupJoinCommands(
            List<ProtocolPullTaskGroupJoinCommandRequest> commands) {
        validatePullTaskGroupJoinCommands(commands);

        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());
        for (ProtocolPullTaskGroupJoinCommandRequest command : commands) {
            String commandId = newCommandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toPullTaskGroupJoinOutboxRow(command, commandId, now));
        }
        Long firstTaskId = commands.get(0).pullTaskId();
        String commonBatchId = commands.stream()
                .allMatch(command -> firstTaskId.equals(command.pullTaskId()))
                ? pullTaskBatchId(firstTaskId) : null;
        return insertPendingRows(commonBatchId, commandIds, rows);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueuePullTaskContactSaveCommands(
            List<ProtocolPullTaskContactSaveCommandRequest> commands) {
        validatePullTaskContactSaveCommands(commands);
        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());
        for (ProtocolPullTaskContactSaveCommandRequest command : commands) {
            String commandId = newCommandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toPullTaskContactSaveOutboxRow(command, commandId, now));
        }
        Long firstTaskId = commands.get(0).pullTaskId();
        String commonBatchId = commands.stream()
                .allMatch(command -> firstTaskId.equals(command.pullTaskId()))
                ? pullTaskBatchId(firstTaskId) : null;
        return insertPendingRows(commonBatchId, commandIds, rows);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueueNormalGroupCreationCommands(
            List<ProtocolNormalGroupCreationCommandRequest> commands) {
        validateNormalGroupCreationCommands(commands);
        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());
        for (ProtocolNormalGroupCreationCommandRequest command : commands) {
            String commandId = newCommandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toNormalGroupCreationOutboxRow(command, commandId, now));
        }
        Long firstTaskId = commands.get(0).taskId();
        String commonBatchId = commands.stream()
                .allMatch(command -> firstTaskId.equals(command.taskId()))
                ? normalGroupCreationBatchId(firstTaskId) : null;
        return insertPendingRows(commonBatchId, commandIds, rows);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueuePullTaskPullerInviteCommands(
            List<ProtocolPullTaskPullerInviteCommandRequest> commands) {
        validatePullTaskPullerInviteCommands(commands);
        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());
        for (ProtocolPullTaskPullerInviteCommandRequest command : commands) {
            String commandId = newCommandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toPullTaskPullerInviteOutboxRow(command, commandId, now));
        }
        Long firstTaskId = commands.get(0).pullTaskId();
        String commonBatchId = commands.stream()
                .allMatch(command -> firstTaskId.equals(command.pullTaskId()))
                ? pullTaskBatchId(firstTaskId) : null;
        return insertPendingRows(commonBatchId, commandIds, rows);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueuePullTaskGroupSettingsCommands(
            List<ProtocolPullTaskGroupSettingsCommandRequest> commands) {
        validatePullTaskGroupSettingsCommands(commands);
        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());
        for (ProtocolPullTaskGroupSettingsCommandRequest command : commands) {
            String commandId = newCommandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toPullTaskGroupSettingsOutboxRow(command, commandId, now));
        }
        Long firstTaskId = commands.get(0).pullTaskId();
        String commonBatchId = commands.stream()
                .allMatch(command -> firstTaskId.equals(command.pullTaskId()))
                ? pullTaskBatchId(firstTaskId) : null;
        return insertPendingRows(commonBatchId, commandIds, rows);
    }

    /** {@inheritDoc} */
    @Override
    public ProtocolCommandOutboxEnqueueResult enqueuePullTaskManagerAdminCommands(
            List<ProtocolPullTaskManagerAdminCommandRequest> commands) {
        validatePullTaskManagerAdminCommands(commands);
        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());
        for (ProtocolPullTaskManagerAdminCommandRequest command : commands) {
            String commandId = newCommandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toPullTaskManagerAdminOutboxRow(command, commandId, now));
        }
        Long firstTaskId = commands.get(0).pullTaskId();
        String commonBatchId = commands.stream()
                .allMatch(command -> firstTaskId.equals(command.pullTaskId()))
                ? pullTaskBatchId(firstTaskId) : null;
        return insertPendingRows(commonBatchId, commandIds, rows);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueuePullTaskBatchAddCommands(
            List<ProtocolPullTaskBatchAddCommandRequest> commands) {
        validatePullTaskBatchAddCommands(commands);
        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());
        for (ProtocolPullTaskBatchAddCommandRequest command : commands) {
            String commandId = newCommandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toPullTaskBatchAddOutboxRow(command, commandId, now));
        }
        Long firstTaskId = commands.get(0).pullTaskId();
        String commonBatchId = commands.stream()
                .allMatch(command -> firstTaskId.equals(command.pullTaskId()))
                ? pullTaskBatchId(firstTaskId) : null;
        return insertPendingRows(commonBatchId, commandIds, rows);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueuePullTaskMaterialAdminCommands(
            List<ProtocolPullTaskMaterialAdminCommandRequest> commands) {
        validatePullTaskMaterialAdminCommands(commands);
        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());
        for (ProtocolPullTaskMaterialAdminCommandRequest command : commands) {
            String commandId = newCommandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toPullTaskMaterialAdminOutboxRow(command, commandId, now));
        }
        Long firstTaskId = commands.get(0).pullTaskId();
        String commonBatchId = commands.stream()
                .allMatch(command -> firstTaskId.equals(command.pullTaskId()))
                ? pullTaskBatchId(firstTaskId) : null;
        return insertPendingRows(commonBatchId, commandIds, rows);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueuePullTaskMemberQueryCommands(
            List<ProtocolPullTaskMemberQueryCommandRequest> commands) {
        validatePullTaskMemberQueryCommands(commands);
        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());
        for (ProtocolPullTaskMemberQueryCommandRequest command : commands) {
            String commandId = newCommandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toPullTaskMemberQueryOutboxRow(command, commandId, now));
        }
        Long firstTaskId = commands.get(0).pullTaskId();
        String commonBatchId = commands.stream()
                .allMatch(command -> firstTaskId.equals(command.pullTaskId()))
                ? pullTaskBatchId(firstTaskId) : null;
        return insertPendingRows(commonBatchId, commandIds, rows);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueuePullTaskCreatorLeaveCommands(
            List<ProtocolPullTaskCreatorLeaveCommandRequest> commands) {
        validatePullTaskCreatorLeaveCommands(commands);
        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());
        for (ProtocolPullTaskCreatorLeaveCommandRequest command : commands) {
            String commandId = newCommandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toPullTaskCreatorLeaveOutboxRow(command, commandId, now));
        }
        Long firstTaskId = commands.get(0).pullTaskId();
        String commonBatchId = commands.stream()
                .allMatch(command -> firstTaskId.equals(command.pullTaskId()))
                ? pullTaskBatchId(firstTaskId) : null;
        return insertPendingRows(commonBatchId, commandIds, rows);
    }

    /**
     * 批量写入协议 backend 已编码的营销消息 outbox 命令。
     *
     * <p>协议 topic、key、backend 和 payload 均由具体 backend 决定；本方法只维护统一
     * outbox envelope、聚合关联和事务提交后 dispatch。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProtocolCommandOutboxEnqueueResult enqueueMessageCommands(
            List<ProtocolMessageOutboxCommand> commands) {
        validateMessageCommands(commands);

        String batchId = commands.size() == 1 ? null : newBatchId();
        long now = System.currentTimeMillis();
        List<String> commandIds = new ArrayList<>(commands.size());
        List<ProtocolCommandOutbox> rows = new ArrayList<>(commands.size());
        Set<String> uniqueCommandIds = new HashSet<>(commands.size());
        for (ProtocolMessageOutboxCommand command : commands) {
            String commandId = command.command().commandId();
            if (!uniqueCommandIds.add(commandId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 重复: " + commandId);
            }
            commandIds.add(commandId);
            rows.add(toMessageOutboxRow(command, batchId, now));
        }
        return insertPendingRows(batchId, commandIds, rows);
    }

    /**
     * 批量插入待发送 outbox 行并注册事务提交后的 dispatch 触发。
     *
     * <p>该方法仍处于业务事务内，只负责落库和 afterCommit 注册；Kafka 发送必须等事务提交后执行，
     * 避免协议层收到已回滚的命令。</p>
     *
     * @param batchId    批量命令归组 ID，单条命令为空
     * @param commandIds 本批次生成的 command_id 列表，用于返回调用方排查
     * @param rows       待插入的 outbox 行
     * @return outbox 入队结果
     * @throws BusinessException 当 command_id 冲突或插入数量不一致时抛出
     */
    private ProtocolCommandOutboxEnqueueResult insertPendingRows(String batchId,
                                                                 List<String> commandIds,
                                                                 List<ProtocolCommandOutbox> rows) {
        assignTraceIds(rows);
        int inserted;
        try {
            inserted = mapper.batchInsertPending(rows);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "协议命令 ID 已存在");
        }
        if (inserted != rows.size()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "协议命令 outbox 写入数量不一致: expected=" + rows.size() + ", inserted=" + inserted);
        }
        // outbox 已写入当前事务,真正 Kafka 发送必须等 commit 后再触发,避免发送已回滚命令。
        dispatchTrigger.dispatchAfterCommit(rows);
        log.info("协议命令 outbox 已写入 batchId={} commandCount={} inserted={}",
                batchId, commandIds.size(), inserted);
        return new ProtocolCommandOutboxEnqueueResult(batchId, commandIds, inserted);
    }

    private void assignTraceIds(List<ProtocolCommandOutbox> rows) {
        Optional<String> currentTraceId = TraceContext.current();
        Map<String, String> traceByAggregate = new HashMap<>();
        for (ProtocolCommandOutbox row : rows) {
            String traceId = currentTraceId.orElseGet(() -> traceByAggregate.computeIfAbsent(
                    traceGroupKey(row), ignored -> TraceIds.newTraceId()));
            row.setTraceId(traceId);
        }
    }

    private String traceGroupKey(ProtocolCommandOutbox row) {
        if (row.getAggregateId() == null) {
            return "command:" + row.getCommandId();
        }
        return row.getAggregateType() + ":" + row.getAggregateId();
    }

    /**
     * 生成 outbox command_id。
     *
     * @return 全局唯一 command_id
     */
    protected String newCommandId() {
        return COMMAND_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 生成 outbox batch_id。
     *
     * @return 批量命令归组 ID
     */
    protected String newBatchId() {
        return BATCH_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 把账号上线命令转换为待发送 outbox 行。
     *
     * <p>Kafka key 使用协议账号 ID，保证同一协议账号的上线命令在 Kafka 分区内有序。</p>
     *
     * @param command   已完成业务校验的上线命令
     * @param commandId 本次生成的 outbox command_id
     * @param batchId   批量命令归组 ID，单条命令为空
     * @param now       创建和更新时间戳
     * @return 待插入的上线 outbox 行
     */
    private ProtocolCommandOutbox toOnlineOutboxRow(ProtocolOnlineCommandRequest command,
                                                    String commandId,
                                                    String batchId,
                                                    long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(TenantContext.get());
        row.setCommandId(commandId);
        row.setBatchId(batchId);
        row.setCommandType(COMMAND_TYPE_ACCOUNT_ONLINE_REQUESTED);
        row.setAggregateType(AGGREGATE_TYPE_ACCOUNT);
        row.setAggregateId(command.accountId());
        row.setKafkaTopic(onlineCommandTopic(command.protocolBackend()));
        row.setKafkaKey(command.protocolAccountId());
        row.setProtocolAccountId(command.protocolAccountId());
        row.setProtocolBackend(command.protocolBackend().name());
        row.setPayloadJson(payloadJson(command));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /**
     * 把账号下线命令转换为待发送 outbox 行。
     *
     * <p>下线命令与上线命令使用相同 aggregate 和 Kafka key 口径，便于 dispatcher 和协议层按账号串行处理。</p>
     *
     * @param command   已完成业务校验的下线命令
     * @param commandId 本次生成的 outbox command_id
     * @param batchId   批量命令归组 ID，单条命令为空
     * @param now       创建和更新时间戳
     * @return 待插入的下线 outbox 行
     */
    private ProtocolCommandOutbox toOfflineOutboxRow(ProtocolOfflineCommandRequest command,
                                                     String commandId,
                                                     String batchId,
                                                     long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(TenantContext.get());
        row.setCommandId(commandId);
        row.setBatchId(batchId);
        row.setCommandType(COMMAND_TYPE_ACCOUNT_OFFLINE_REQUESTED);
        row.setAggregateType(AGGREGATE_TYPE_ACCOUNT);
        row.setAggregateId(command.accountId());
        row.setKafkaTopic(offlineCommandTopic(command.protocolBackend()));
        row.setKafkaKey(command.protocolAccountId());
        row.setProtocolAccountId(command.protocolAccountId());
        row.setProtocolBackend(command.protocolBackend().name());
        row.setPayloadJson(payloadJson(command));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private ProtocolCommandOutbox toGroupHealthCheckOutboxRow(ProtocolGroupHealthCheckCommandRequest command,
                                                              String commandId,
                                                              String batchId,
                                                              long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(TenantContext.get());
        row.setCommandId(commandId);
        row.setBatchId(batchId);
        row.setCommandType(COMMAND_TYPE_GROUP_HEALTH_CHECK_REQUESTED);
        row.setAggregateType(AGGREGATE_TYPE_GROUP_LINK);
        row.setAggregateId(command.groupLinkId());
        row.setKafkaTopic(masterCommandProperties.getTopic());
        row.setKafkaKey(command.protocolAccountId());
        row.setProtocolAccountId(command.protocolAccountId());
        row.setProtocolBackend(ProtocolBackend.WEB.name());
        row.setPayloadJson(payloadJson(command));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private ProtocolCommandOutbox toGroupSnapshotOutboxRow(
            ProtocolGroupSnapshotCommandRequest command,
            String commandId,
            String batchId,
            long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(TenantContext.get());
        row.setCommandId(commandId);
        row.setBatchId(batchId);
        row.setCommandType(COMMAND_TYPE_GROUP_SNAPSHOT_REQUESTED);
        row.setAggregateType(switch (command.taskType()) {
            case "GROUP_METADATA_SYNC" -> AGGREGATE_TYPE_GROUP_METADATA_SYNC_TASK;
            case "GROUP_BATCH_TASK_ITEM" -> AGGREGATE_TYPE_GROUP_BATCH_TASK_ITEM;
            default -> throw new IllegalArgumentException(
                    "unsupported group snapshot taskType: " + command.taskType());
        });
        row.setAggregateId(command.taskId());
        row.setKafkaTopic(command.protocolBackend() == ProtocolBackend.ANDROID
                ? androidCommandProperties.getGroupActionTopic()
                : masterCommandProperties.getTopic());
        row.setKafkaKey(command.protocolAccountId());
        row.setProtocolAccountId(command.protocolAccountId());
        row.setProtocolBackend(command.protocolBackend().name());
        row.setPayloadJson(payloadJson(command));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /**
     * 把账号群列表同步命令转换为待发送 outbox 行。
     *
     * <p>topic 必须按后端分流,与上线命令同一口径:Web 走 master 由其按 owner 路由 worker,
     * 安卓走安卓生命周期 topic 由持号的 fleet 节点执行。发错 topic 不会报错,
     * 只会在 Web master 侧记一条查无 owner 的 warn,该号的群列表从此不再刷新。</p>
     */
    private ProtocolCommandOutbox toAccountGroupSyncOutboxRow(ProtocolAccountGroupSyncCommandRequest command,
                                                              String commandId,
                                                              String batchId,
                                                              long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(TenantContext.get());
        row.setCommandId(commandId);
        row.setBatchId(batchId);
        row.setCommandType(COMMAND_TYPE_ACCOUNT_GROUPS_SYNC_REQUESTED);
        row.setAggregateType(AGGREGATE_TYPE_ACCOUNT);
        row.setAggregateId(command.accountId());
        row.setKafkaTopic(accountGroupSyncCommandTopic(command.protocolBackend()));
        row.setKafkaKey(command.protocolAccountId());
        row.setProtocolAccountId(command.protocolAccountId());
        row.setProtocolBackend(command.protocolBackend().name());
        row.setPayloadJson(payloadJson(command));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /**
     * 把统一进群命令转换为待发送 outbox 行。
     *
     * <p>Web 命令进入 master topic，由 master 按协议账号路由 worker；Android 命令直接进入 Android
     * 进群命令 topic。两端都使用 protocolAccountId 作为 Kafka key，使单账号命令在分区内有序。</p>
     *
     * @param command 已完成字段校验的进群命令
     * @param commandId 本次生成的全局命令 ID
     * @param now 创建和更新时间（epoch 毫秒）
     * @return 状态为 PENDING 的进群 outbox 行
     */
    private ProtocolCommandOutbox toGroupJoinOutboxRow(ProtocolGroupJoinCommandRequest command,
                                                        String commandId,
                                                        long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(TenantContext.get());
        row.setCommandId(commandId);
        row.setBatchId(joinTaskBatchId(command.joinTaskId()));
        row.setCommandType(COMMAND_TYPE_GROUP_JOIN_REQUESTED);
        row.setAggregateType(AGGREGATE_TYPE_JOIN_TASK_RESULT);
        row.setAggregateId(command.joinTaskResultId());
        row.setKafkaTopic(command.protocolBackend() == ProtocolBackend.ANDROID
                ? androidCommandProperties.getGroupJoinTopic() : masterCommandProperties.getTopic());
        row.setKafkaKey(command.protocolAccountId());
        row.setProtocolAccountId(command.protocolAccountId());
        row.setProtocolBackend(command.protocolBackend().name());
        row.setPayloadJson(payloadJson(command));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /** 把普通拉群管理员踩链接引用转换为待发送 Outbox 行。 */
    private ProtocolCommandOutbox toPullTaskGroupJoinOutboxRow(
            ProtocolPullTaskGroupJoinCommandRequest command,
            String commandId,
            long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(command.tenantId());
        row.setCommandId(commandId);
        row.setBatchId(pullTaskBatchId(command.pullTaskId()));
        row.setCommandType(COMMAND_TYPE_GROUP_JOIN_REQUESTED);
        row.setAggregateType(AGGREGATE_TYPE_PULL_TASK_ACCOUNT_ACTION);
        row.setAggregateId(command.actionId());
        row.setKafkaTopic(command.account().backend() == ProtocolBackend.ANDROID
                ? androidCommandProperties.getGroupJoinTopic() : masterCommandProperties.getTopic());
        row.setKafkaKey(command.account().protocolAccountId());
        row.setProtocolAccountId(command.account().protocolAccountId());
        row.setProtocolBackend(command.account().backend().name());
        row.setPayloadJson(payloadJson(command.reference()));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /** 把普通拉群联系人动作引用转换为待发送 Outbox 行。 */
    private ProtocolCommandOutbox toPullTaskContactSaveOutboxRow(
            ProtocolPullTaskContactSaveCommandRequest command,
            String commandId,
            long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(command.tenantId());
        row.setCommandId(commandId);
        row.setBatchId(pullTaskBatchId(command.pullTaskId()));
        row.setCommandType(COMMAND_TYPE_CONTACT_SAVE_REQUESTED);
        row.setAggregateType(AGGREGATE_TYPE_PULL_TASK_ACCOUNT_ACTION);
        row.setAggregateId(command.actionId());
        row.setKafkaTopic(command.actor().backend() == ProtocolBackend.ANDROID
                ? androidCommandProperties.getGroupActionTopic() : masterCommandProperties.getTopic());
        row.setKafkaKey(command.actor().protocolAccountId());
        row.setProtocolAccountId(command.actor().protocolAccountId());
        row.setProtocolBackend(command.actor().backend().name());
        row.setPayloadJson(payloadJson(command.reference()));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /** 把新建普群动作引用转换为按实际执行协议路由的待发送 Outbox 行。 */
    private ProtocolCommandOutbox toNormalGroupCreationOutboxRow(
            ProtocolNormalGroupCreationCommandRequest command,
            String commandId,
            long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(command.tenantId());
        row.setCommandId(commandId);
        row.setBatchId(normalGroupCreationBatchId(command.taskId()));
        row.setCommandType(COMMAND_TYPE_NORMAL_GROUP_CREATION_REQUESTED);
        row.setAggregateType(AGGREGATE_TYPE_NORMAL_GROUP_CREATION_ITEM);
        row.setAggregateId(command.itemId());
        row.setKafkaTopic(switch (command.actor().backend()) {
            case WEB -> normalGroupCreationKafkaProperties.getWebCommandTopic();
            case ANDROID -> normalGroupCreationKafkaProperties.getAndroidCommandTopic();
        });
        row.setKafkaKey(command.actor().protocolAccountId());
        row.setProtocolAccountId(command.actor().protocolAccountId());
        row.setProtocolBackend(command.actor().backend().name());
        row.setPayloadJson(payloadJson(command.reference()));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /** 把普通拉群管理员邀请动作引用转换为待发送 Outbox 行。 */
    private ProtocolCommandOutbox toPullTaskPullerInviteOutboxRow(
            ProtocolPullTaskPullerInviteCommandRequest command,
            String commandId,
            long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(command.tenantId());
        row.setCommandId(commandId);
        row.setBatchId(pullTaskBatchId(command.pullTaskId()));
        row.setCommandType(COMMAND_TYPE_GROUP_PARTICIPANTS_REQUESTED);
        row.setAggregateType(AGGREGATE_TYPE_PULL_TASK_ACCOUNT_ACTION);
        row.setAggregateId(command.actionId());
        row.setKafkaTopic(command.actor().backend() == ProtocolBackend.ANDROID
                ? androidCommandProperties.getGroupActionTopic() : masterCommandProperties.getTopic());
        row.setKafkaKey(command.actor().protocolAccountId());
        row.setProtocolAccountId(command.actor().protocolAccountId());
        row.setProtocolBackend(command.actor().backend().name());
        row.setPayloadJson(payloadJson(command.reference()));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /** 把普通拉群管理员设置动作引用转换为待发送 Outbox 行。 */
    private ProtocolCommandOutbox toPullTaskManagerAdminOutboxRow(
            ProtocolPullTaskManagerAdminCommandRequest command,
            String commandId,
            long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(command.tenantId());
        row.setCommandId(commandId);
        row.setBatchId(pullTaskBatchId(command.pullTaskId()));
        row.setCommandType(COMMAND_TYPE_GROUP_PARTICIPANTS_REQUESTED);
        row.setAggregateType(AGGREGATE_TYPE_PULL_TASK_ACCOUNT_ACTION);
        row.setAggregateId(command.actionId());
        row.setKafkaTopic(command.actor().backend() == ProtocolBackend.ANDROID
                ? androidCommandProperties.getGroupActionTopic() : masterCommandProperties.getTopic());
        row.setKafkaKey(command.actor().protocolAccountId());
        row.setProtocolAccountId(command.actor().protocolAccountId());
        row.setProtocolBackend(command.actor().backend().name());
        row.setPayloadJson(payloadJson(command.reference()));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /** 把普通拉群群设置动作引用转换为待发送 Outbox 行。 */
    private ProtocolCommandOutbox toPullTaskGroupSettingsOutboxRow(
            ProtocolPullTaskGroupSettingsCommandRequest command,
            String commandId,
            long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(command.tenantId());
        row.setCommandId(commandId);
        row.setBatchId(pullTaskBatchId(command.pullTaskId()));
        row.setCommandType(COMMAND_TYPE_GROUP_SETTINGS_REQUESTED);
        row.setAggregateType(AGGREGATE_TYPE_PULL_TASK_ACCOUNT_ACTION);
        row.setAggregateId(command.actionId());
        row.setKafkaTopic(command.manager().backend() == ProtocolBackend.ANDROID
                ? androidCommandProperties.getGroupActionTopic() : masterCommandProperties.getTopic());
        row.setKafkaKey(command.manager().protocolAccountId());
        row.setProtocolAccountId(command.manager().protocolAccountId());
        row.setProtocolBackend(command.manager().backend().name());
        row.setPayloadJson(payloadJson(command.reference()));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /** 把普通拉群批量拉人调用引用转换为待发送 Outbox 行。 */
    private ProtocolCommandOutbox toPullTaskBatchAddOutboxRow(
            ProtocolPullTaskBatchAddCommandRequest command,
            String commandId,
            long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(command.tenantId());
        row.setCommandId(commandId);
        row.setBatchId(pullTaskBatchId(command.pullTaskId()));
        row.setCommandType(COMMAND_TYPE_GROUP_PARTICIPANTS_REQUESTED);
        row.setAggregateType(AGGREGATE_TYPE_PULL_TASK_PULL_CALL);
        row.setAggregateId(command.pullCallId());
        row.setKafkaTopic(command.actor().backend() == ProtocolBackend.ANDROID
                ? androidCommandProperties.getGroupActionTopic() : masterCommandProperties.getTopic());
        row.setKafkaKey(command.actor().protocolAccountId());
        row.setProtocolAccountId(command.actor().protocolAccountId());
        row.setProtocolBackend(command.actor().backend().name());
        row.setPayloadJson(payloadJson(command.reference()));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /** 把普通拉群料子提权引用转换为待发送 Outbox 行。 */
    private ProtocolCommandOutbox toPullTaskMaterialAdminOutboxRow(
            ProtocolPullTaskMaterialAdminCommandRequest command,
            String commandId,
            long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(command.tenantId());
        row.setCommandId(commandId);
        row.setBatchId(pullTaskBatchId(command.pullTaskId()));
        row.setCommandType(COMMAND_TYPE_GROUP_PARTICIPANTS_REQUESTED);
        row.setAggregateType(AGGREGATE_TYPE_PULL_TASK_MATERIAL_MEMBER);
        row.setAggregateId(command.materialId());
        row.setKafkaTopic(command.actor().backend() == ProtocolBackend.ANDROID
                ? androidCommandProperties.getGroupActionTopic() : masterCommandProperties.getTopic());
        row.setKafkaKey(command.actor().protocolAccountId());
        row.setProtocolAccountId(command.actor().protocolAccountId());
        row.setProtocolBackend(command.actor().backend().name());
        row.setPayloadJson(payloadJson(command.reference()));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /** 把普通拉群成员查询引用转换为按协议后端路由的待发送 Outbox 行。 */
    private ProtocolCommandOutbox toPullTaskMemberQueryOutboxRow(
            ProtocolPullTaskMemberQueryCommandRequest command,
            String commandId,
            long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(command.tenantId());
        row.setCommandId(commandId);
        row.setBatchId(pullTaskBatchId(command.pullTaskId()));
        row.setCommandType(COMMAND_TYPE_GROUP_MEMBERS_QUERY_REQUESTED);
        row.setAggregateType(AGGREGATE_TYPE_PULL_TASK_MEMBER_QUERY);
        row.setAggregateId(command.queryId());
        row.setKafkaTopic(command.actor().backend() == ProtocolBackend.ANDROID
                ? androidCommandProperties.getGroupActionTopic()
                : masterCommandProperties.getTopic());
        row.setKafkaKey(command.actor().protocolAccountId());
        row.setProtocolAccountId(command.actor().protocolAccountId());
        row.setProtocolBackend(command.actor().backend().name());
        row.setPayloadJson(payloadJson(command.reference()));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /** 把群主退群链路动作引用转换为按建群者协议后端路由的 Outbox 行。 */
    private ProtocolCommandOutbox toPullTaskCreatorLeaveOutboxRow(
            ProtocolPullTaskCreatorLeaveCommandRequest command,
            String commandId,
            long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(command.tenantId());
        row.setCommandId(commandId);
        row.setBatchId(pullTaskBatchId(command.pullTaskId()));
        row.setCommandType(command.action()
                == ProtocolPullTaskCreatorLeaveCommandRequest.Action.PROMOTE
                ? COMMAND_TYPE_GROUP_PARTICIPANTS_REQUESTED
                : COMMAND_TYPE_GROUP_LEAVE_REQUESTED);
        row.setAggregateType(AGGREGATE_TYPE_PULL_TASK_ACCOUNT_ACTION);
        row.setAggregateId(command.actionId());
        row.setKafkaTopic(command.actor().backend() == ProtocolBackend.ANDROID
                ? androidCommandProperties.getGroupActionTopic() : masterCommandProperties.getTopic());
        row.setKafkaKey(command.actor().protocolAccountId());
        row.setProtocolAccountId(command.actor().protocolAccountId());
        row.setProtocolBackend(command.actor().backend().name());
        row.setPayloadJson(payloadJson(command.reference()));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(IMMEDIATE_RETRY_AT);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /**
     * 生成进群任务稳定批次 ID，便于跨多轮随机间隔派发时按任务排查。
     *
     * @param joinTaskId 进群任务 ID
     * @return {@code join-task:{id}} 格式批次 ID
     */
    private static String joinTaskBatchId(Long joinTaskId) {
        return "join-task:" + joinTaskId;
    }

    /** 生成普通拉群任务稳定批次 ID。 */
    private static String pullTaskBatchId(Long pullTaskId) {
        return "pull-task:" + pullTaskId;
    }

    /** 生成新建普群任务稳定批次 ID。 */
    private static String normalGroupCreationBatchId(Long taskId) {
        return "normal-group-creation:" + taskId;
    }

    private ProtocolCommandOutbox toMessageOutboxRow(
            ProtocolMessageOutboxCommand outboxCommand,
            String batchId,
            long now) {
        com.armada.platform.protocol.model.command.MessageSendCommand command = outboxCommand.command();
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(TenantContext.get());
        row.setCommandId(command.commandId());
        row.setBatchId(batchId);
        row.setCommandType(command.target().kind() ==
                com.armada.platform.protocol.model.command.MessageSendCommand.TargetKind.STATUS
                ? COMMAND_TYPE_STATUS_PUBLISH_REQUESTED
                : COMMAND_TYPE_MESSAGE_SEND_REQUESTED);
        if (command.correlation().groupCreation() != null) {
            row.setAggregateType(AGGREGATE_TYPE_GROUP_CREATION_MARKETING_ITEM);
            row.setAggregateId(command.correlation().groupCreation().itemId());
        } else if (command.correlation().historicalGroup() != null) {
            row.setAggregateType(AGGREGATE_TYPE_HISTORICAL_GROUP_PULL_MEMBER);
            row.setAggregateId(command.correlation().historicalGroup().memberId());
        } else if (command.correlation().contactTask() != null) {
            row.setAggregateType(AGGREGATE_TYPE_CONTACT_TASK_RECIPIENT);
            row.setAggregateId(command.correlation().contactTask().recipientId());
        } else if (command.correlation().feedTask() != null) {
            row.setAggregateType(AGGREGATE_TYPE_FEED_TASK_ACCOUNT);
            row.setAggregateId(command.correlation().feedTask().taskAccountId());
        } else if (command.correlation().hyperlink() != null) {
            row.setAggregateType(AGGREGATE_TYPE_HYPERLINK_TASK_RECIPIENT);
            row.setAggregateId(command.correlation().hyperlink().recipientId());
        } else {
            row.setAggregateType(AGGREGATE_TYPE_MARKETING_SEND_ATTEMPT);
            row.setAggregateId(command.correlation().marketing().attemptId());
        }
        row.setKafkaTopic(outboxCommand.kafkaTopic());
        row.setKafkaKey(outboxCommand.kafkaKey());
        row.setProtocolAccountId(command.account().protocolAccountId());
        row.setProtocolBackend(outboxCommand.backend().name());
        row.setPayloadJson(payloadJson(outboxCommand.payload()));
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(Math.max(IMMEDIATE_RETRY_AT, command.notBeforeAt()));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /**
     * 生成协议层账号上线命令 payload JSON。
     *
     * <p>payload 只放协议层消费所需字段，代理 ID 随上线命令下发，供协议层按指定 IP 建链。</p>
     *
     * @param command 已完成业务校验的上线命令
     * @return 上线命令 payload JSON
     * @throws BusinessException 当 payload 无法序列化时抛出
     */
    private String payloadJson(ProtocolOnlineCommandRequest command) {
        // attempt ID 是 Armada 侧上线链路的排查主键,不含凭据或代理密钥,允许随 outbox/Kafka 透传。
        ProtocolOnlineCommandPayload payload = new ProtocolOnlineCommandPayload(
                command.accountId(),
                command.protocolAccountId(),
                command.credentialFormat(),
                command.proxyId(),
                command.source(),
                command.onlineAttemptId(),
                command.previousOnlineAttemptId(),
                command.protocolBackend(),
                command.isBusiness(),
                command.declaredAccountType(),
                command.detectAccountType(),
                command.deviceOs());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议命令 payload 序列化失败");
        }
    }

    /**
     * 生成协议层账号下线命令 payload JSON。
     *
     * <p>下线只需要账号定位信息和来源，不携带代理信息；IP 释放由协议层状态回写后的业务流程处理。</p>
     *
     * @param command 已完成业务校验的下线命令
     * @return 下线命令 payload JSON
     * @throws BusinessException 当 payload 无法序列化时抛出
     */
    private String payloadJson(ProtocolOfflineCommandRequest command) {
        ProtocolOfflineCommandPayload payload = new ProtocolOfflineCommandPayload(
                command.accountId(),
                command.protocolAccountId(),
                command.source(),
                command.protocolBackend());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议命令 payload 序列化失败");
        }
    }

    /**
     * 生成协议层群健康检查命令 payload JSON。
     *
     * <p>payload 只携带群链接和操作账号引用,协议层 master 据此路由到 owner worker 执行 metadata。</p>
     *
     * @param command 已完成业务校验的群健康检查命令
     * @return 群健康检查命令 payload JSON
     * @throws BusinessException 当 payload 无法序列化时抛出
     */
    private String payloadJson(ProtocolGroupHealthCheckCommandRequest command) {
        ProtocolGroupHealthCheckCommandPayload payload = new ProtocolGroupHealthCheckCommandPayload(
                command.tenantId(),
                command.groupLinkId(),
                command.groupJid(),
                command.accountId(),
                command.protocolAccountId(),
                command.source());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议命令 payload 序列化失败");
        }
    }

    /**
     * 生成协议层账号当前群同步命令 payload JSON。
     *
     * <p>payload 只携带本地账号引用和来源,协议层 master 根据 {@code protocolAccountId}
     * 路由到 owner worker,由 worker 执行账号当前群列表读取。</p>
     *
     * @param command 已完成业务校验的账号群同步命令
     * @return 账号群同步命令 payload JSON
     * @throws BusinessException 当 payload 无法序列化时抛出
     */
    private String payloadJson(ProtocolAccountGroupSyncCommandRequest command) {
        ProtocolAccountGroupSyncCommandPayload payload = new ProtocolAccountGroupSyncCommandPayload(
                command.tenantId(),
                command.accountId(),
                command.protocolAccountId(),
                command.source());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议命令 payload 序列化失败");
        }
    }

    /**
     * 序列化已经定义好 wire 字段的进群或营销命令载荷。
     *
     * @param payload 协议层命令 payload
     * @return JSON 文本
     * @throws BusinessException JSON 序列化失败时抛出，阻止写入不可消费的 outbox 行
     */
    private String payloadJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议命令 payload 序列化失败");
        }
    }

    /**
     * 校验账号上线命令批次。
     *
     * <p>批次不能为空且不能超过单批上限，避免一次事务写入过大 outbox 批次导致 dispatcher 压力失控。</p>
     *
     * @param commands 待入队的上线命令列表
     * @throws BusinessException 当批次为空、超限或单条命令缺少必要字段时抛出
     */
    private void validateOnlineCommands(List<ProtocolOnlineCommandRequest> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议上线命令不能为空");
        }
        if (commands.size() > MAX_ACCOUNT_LIFECYCLE_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "协议上线命令不能超过 " + MAX_ACCOUNT_LIFECYCLE_COMMANDS_PER_BATCH + " 条");
        }
        for (ProtocolOnlineCommandRequest command : commands) {
            validateOnlineCommand(command);
        }
    }

    /**
     * 校验单条账号上线命令的协议层必需字段。
     *
     * <p>上线命令必须带账号、协议账号、凭据格式、代理 ID 和来源，确保 dispatcher 发送后协议层可直接执行。</p>
     *
     * @param command 待校验的上线命令
     * @throws BusinessException 当命令为空或缺少必要字段时抛出
     */
    private void validateOnlineCommand(ProtocolOnlineCommandRequest command) {
        if (command == null
                || command.accountId() == null
                || isBlank(command.protocolAccountId())
                || command.credentialFormat() == null
                || command.proxyId() == null
                || isBlank(command.source())) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议上线命令缺少必要字段");
        }
        if (isBlank(command.onlineAttemptId())) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议上线命令缺少 onlineAttemptId");
        }
    }

    /**
     * 校验账号下线命令批次。
     *
     * <p>批次约束与上线命令保持一致，保证 outbox 写入和后续 dispatcher 拉取都按受控批量执行。</p>
     *
     * @param commands 待入队的下线命令列表
     * @throws BusinessException 当批次为空、超限或单条命令缺少必要字段时抛出
     */
    private void validateOfflineCommands(List<ProtocolOfflineCommandRequest> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议下线命令不能为空");
        }
        if (commands.size() > MAX_ACCOUNT_LIFECYCLE_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "协议下线命令不能超过 " + MAX_ACCOUNT_LIFECYCLE_COMMANDS_PER_BATCH + " 条");
        }
        for (ProtocolOfflineCommandRequest command : commands) {
            validateOfflineCommand(command);
        }
    }

    /**
     * 校验单条账号下线命令的协议层必需字段。
     *
     * <p>下线命令只需要账号定位信息和来源，不要求代理 ID 或凭据字段。</p>
     *
     * @param command 待校验的下线命令
     * @throws BusinessException 当命令为空或缺少必要字段时抛出
     */
    private void validateOfflineCommand(ProtocolOfflineCommandRequest command) {
        if (command == null
                || command.accountId() == null
                || isBlank(command.protocolAccountId())
                || isBlank(command.source())) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议下线命令缺少必要字段");
        }
    }

    /**
     * 校验群链接健康检查命令批次。
     *
     * <p>批次约束与账号命令保持一致,避免单轮巡检写入过大的 outbox 批次。</p>
     *
     * @param commands 待入队的群健康检查命令列表
     * @throws BusinessException 当批次为空、超限或单条命令缺少必要字段时抛出
     */
    private void validateGroupHealthCheckCommands(List<ProtocolGroupHealthCheckCommandRequest> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "群链接健康检查命令不能为空");
        }
        if (commands.size() > MAX_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "群链接健康检查命令不能超过 " + MAX_COMMANDS_PER_BATCH + " 条");
        }
        for (ProtocolGroupHealthCheckCommandRequest command : commands) {
            validateGroupHealthCheckCommand(command);
        }
    }

    private void validateGroupSnapshotCommands(List<ProtocolGroupSnapshotCommandRequest> commands) {
        if (commands == null || commands.isEmpty() || commands.size() > MAX_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION, "群快照命令为空或超过批量上限");
        }
        Set<String> allowedScopes = Set.of("METADATA", "INVITE_CODE");
        Set<String> allowedSources = Set.of(
                "MANUAL_INFO_REFRESH", "MANUAL_INVITE_REFRESH", "REPAIR", "BACKFILL",
                "INVITE_CANDIDATE_ROTATION");
        Set<String> allowedTaskTypes = Set.of("GROUP_METADATA_SYNC", "GROUP_BATCH_TASK_ITEM");
        for (ProtocolGroupSnapshotCommandRequest command : commands) {
            if (command == null || command.tenantId() == null || command.tenantId() <= 0
                    || command.accountId() == null || command.accountId() <= 0
                    || command.groupLinkId() == null || command.groupLinkId() <= 0
                    || isBlank(command.groupJid()) || !command.groupJid().endsWith("@g.us")
                    || command.scopes() == null || command.scopes().isEmpty()
                    || command.scopes().size() > allowedScopes.size()
                    || command.scopes().stream().anyMatch(scope -> !allowedScopes.contains(scope))
                    || new HashSet<>(command.scopes()).size() != command.scopes().size()
                    || !allowedSources.contains(command.source())
                    || !allowedTaskTypes.contains(command.taskType())
                    || command.taskId() == null || command.taskId() <= 0
                    || command.attemptNo() <= 0 || isBlank(command.protocolAccountId())
                    || isBlank(command.wsPhone())
                    || command.protocolBackend() == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "群快照命令缺少必要字段或字段非法");
            }
        }
    }

    /**
     * 校验单条群链接健康检查命令的协议层必需字段。
     *
     * <p>群健康检查必须携带本地群链接、群 JID 和协议账号,保证 master 可以路由且回写能命中本地记录。</p>
     *
     * @param command 待校验的群健康检查命令
     * @throws BusinessException 当命令为空或缺少必要字段时抛出
     */
    private void validateGroupHealthCheckCommand(ProtocolGroupHealthCheckCommandRequest command) {
        if (command == null
                || command.tenantId() == null
                || command.groupLinkId() == null
                || isBlank(command.groupJid())
                || command.accountId() == null
                || isBlank(command.protocolAccountId())
                    || isBlank(command.source())) {
            throw new BusinessException(ErrorCode.VALIDATION, "群链接健康检查命令缺少必要字段");
        }
    }

    /**
     * 校验账号当前群同步命令批次。
     *
     * <p>批次约束与其它协议命令保持一致,避免单轮账号群刷新写入过大的 outbox 批次。</p>
     *
     * @param commands 待入队的账号群同步命令列表
     * @throws BusinessException 当批次为空、超限或单条命令缺少必要字段时抛出
     */
    private void validateAccountGroupSyncCommands(List<ProtocolAccountGroupSyncCommandRequest> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群同步命令不能为空");
        }
        if (commands.size() > MAX_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "账号群同步命令不能超过 " + MAX_COMMANDS_PER_BATCH + " 条");
        }
        for (ProtocolAccountGroupSyncCommandRequest command : commands) {
            validateAccountGroupSyncCommand(command);
        }
    }

    /**
     * 校验单条账号当前群同步命令的协议层必需字段。
     *
     * <p>tenantId/accountId 用于回写定位,protocolAccountId 用于 master owner 路由。</p>
     *
     * @param command 待校验的账号群同步命令
     * @throws BusinessException 当命令为空或缺少必要字段时抛出
     */
    private void validateAccountGroupSyncCommand(ProtocolAccountGroupSyncCommandRequest command) {
        if (command == null
                || command.tenantId() == null
                || command.accountId() == null
                || isBlank(command.protocolAccountId())
                || isBlank(command.source())) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群同步命令缺少必要字段");
        }
    }

    /**
     * 校验统一进群命令批次和协议执行所需字段。
     *
     * <p>source 固定为 join_task，防止其它业务借用该命令类型却无法复用任务结果回写语义。路由后端、
     * 协议账号、手机号和邀请码均在写 outbox 前确认，避免无效命令进入异步链路。</p>
     *
     * @param commands 待写入 outbox 的进群命令，最多 500 条
     * @throws BusinessException 批次为空、超限或任一命令缺少必要字段时抛出
     */
    private void validateGroupJoinCommands(List<ProtocolGroupJoinCommandRequest> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "进群协议命令不能为空");
        }
        if (commands.size() > MAX_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "进群协议命令不能超过 " + MAX_COMMANDS_PER_BATCH + " 条");
        }
        for (ProtocolGroupJoinCommandRequest command : commands) {
            if (command == null
                    || command.tenantId() == null
                    || command.joinTaskId() == null
                    || command.joinTaskResultId() == null
                    || command.accountId() == null
                    || isBlank(command.protocolAccountId())
                    || isBlank(command.wsPhone())
                    || command.protocolBackend() == null
                    || isBlank(command.inviteCode())
                    || command.attemptNo() <= 0
                    || !ProtocolGroupJoinCommandRequest.SOURCE_JOIN_TASK.equals(command.source())) {
                throw new BusinessException(ErrorCode.VALIDATION, "进群协议命令缺少必要字段");
            }
        }
    }

    /** 校验普通群链接管理员踩链接命令和当前租户。 */
    private void validatePullTaskGroupJoinCommands(
            List<ProtocolPullTaskGroupJoinCommandRequest> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "普通拉群进群协议命令不能为空");
        }
        if (commands.size() > MAX_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "普通拉群进群协议命令不能超过 " + MAX_COMMANDS_PER_BATCH + " 条");
        }
        Long tenantId = TenantContext.get();
        for (ProtocolPullTaskGroupJoinCommandRequest command : commands) {
            if (command == null
                    || command.tenantId() == null
                    || !command.tenantId().equals(tenantId)
                    || command.pullTaskId() == null
                    || command.groupExecutionId() == null
                    || command.actionId() == null
                    || command.account() == null
                    || command.account().armadaAccountId() <= 0
                    || isBlank(command.account().protocolAccountId())
                    || isBlank(command.account().wsPhone())) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "普通拉群进群协议命令缺少必要字段或租户不一致");
            }
        }
    }

    /** 校验普通拉群联系人保存命令和当前租户。 */
    private void validatePullTaskContactSaveCommands(
            List<ProtocolPullTaskContactSaveCommandRequest> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "普通拉群联系人协议命令不能为空");
        }
        if (commands.size() > MAX_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "普通拉群联系人协议命令不能超过 " + MAX_COMMANDS_PER_BATCH + " 条");
        }
        Long tenantId = TenantContext.get();
        for (ProtocolPullTaskContactSaveCommandRequest command : commands) {
            if (command == null
                    || command.tenantId() == null
                    || !command.tenantId().equals(tenantId)
                    || command.pullTaskId() == null
                    || command.groupExecutionId() == null
                    || command.actionId() == null
                    || command.actor() == null
                    || command.actor().armadaAccountId() <= 0
                    || isBlank(command.actor().protocolAccountId())
                    || isBlank(command.actor().wsPhone())) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "普通拉群联系人协议命令缺少必要字段或租户不一致");
            }
        }
    }

    /** 校验新建普群动作、联系人方向、路由账号和当前租户。 */
    private void validateNormalGroupCreationCommands(
            List<ProtocolNormalGroupCreationCommandRequest> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "新建普群协议命令不能为空");
        }
        if (commands.size() > MAX_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "新建普群协议命令不能超过 " + MAX_COMMANDS_PER_BATCH + " 条");
        }
        Long tenantId = TenantContext.get();
        Set<String> actions = Set.of(
                "CONTACT_PREPARE", "GROUP_CREATE", "GROUP_SETTINGS_APPLY", "GROUP_LEAVE");
        Set<String> directions = Set.of(
                "CREATOR_SAVE_MEMBER",
                "MEMBER_SAVE_CREATOR",
                "CREATOR_SAVE_SECONDARY",
                "SECONDARY_SAVE_CREATOR",
                "SECONDARY_SAVE_ANCHOR",
                "ANCHOR_SAVE_SECONDARY");
        for (ProtocolNormalGroupCreationCommandRequest command : commands) {
            boolean contactPrepare = command != null && "CONTACT_PREPARE".equals(command.action());
            if (command == null
                    || command.tenantId() == null
                    || !command.tenantId().equals(tenantId)
                    || command.taskId() == null || command.taskId() <= 0
                    || command.itemId() == null || command.itemId() <= 0
                    || !actions.contains(command.action())
                    || (contactPrepare && (command.memberId() == null || command.memberId() <= 0
                            || !directions.contains(command.direction())))
                    || (!contactPrepare && (command.memberId() != null || command.direction() != null))
                    || command.actor() == null
                    || command.actor().armadaAccountId() == null
                    || command.actor().armadaAccountId() <= 0
                    || command.actor().backend() == null
                    || isBlank(command.actor().protocolAccountId())
                    || isBlank(command.actor().wsPhone())) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "新建普群协议命令缺少必要字段、动作非法或租户不一致");
            }
        }
    }

    /** 校验普通拉群管理员邀请命令和当前租户。 */
    private void validatePullTaskPullerInviteCommands(
            List<ProtocolPullTaskPullerInviteCommandRequest> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "普通拉群邀请协议命令不能为空");
        }
        if (commands.size() > MAX_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "普通拉群邀请协议命令不能超过 " + MAX_COMMANDS_PER_BATCH + " 条");
        }
        Long tenantId = TenantContext.get();
        for (ProtocolPullTaskPullerInviteCommandRequest command : commands) {
            if (command == null
                    || command.tenantId() == null
                    || !command.tenantId().equals(tenantId)
                    || command.pullTaskId() == null
                    || command.groupExecutionId() == null
                    || command.actionId() == null
                    || command.actor() == null
                    || command.actor().armadaAccountId() <= 0
                    || isBlank(command.actor().protocolAccountId())
                    || isBlank(command.actor().wsPhone())) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "普通拉群邀请协议命令缺少必要字段或租户不一致");
            }
        }
    }

    /** 校验普通拉群管理员设置命令和当前租户。 */
    private void validatePullTaskGroupSettingsCommands(
            List<ProtocolPullTaskGroupSettingsCommandRequest> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "普通拉群群设置协议命令不能为空");
        }
        if (commands.size() > MAX_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "普通拉群群设置协议命令不能超过 " + MAX_COMMANDS_PER_BATCH + " 条");
        }
        Long tenantId = TenantContext.get();
        for (ProtocolPullTaskGroupSettingsCommandRequest command : commands) {
            if (command == null
                    || command.tenantId() == null
                    || !command.tenantId().equals(tenantId)
                    || command.pullTaskId() == null
                    || command.groupExecutionId() == null
                    || command.actionId() == null
                    || command.manager() == null
                    || command.manager().armadaAccountId() <= 0
                    || isBlank(command.manager().protocolAccountId())
                    || isBlank(command.manager().wsPhone())) {
                throw new BusinessException(ErrorCode.VALIDATION, "普通拉群群设置协议命令字段非法");
            }
        }
    }

    private void validatePullTaskManagerAdminCommands(
            List<ProtocolPullTaskManagerAdminCommandRequest> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "普通拉群管理员设置协议命令不能为空");
        }
        if (commands.size() > MAX_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "普通拉群管理员设置协议命令不能超过 " + MAX_COMMANDS_PER_BATCH + " 条");
        }
        Long tenantId = TenantContext.get();
        for (ProtocolPullTaskManagerAdminCommandRequest command : commands) {
            if (command == null
                    || command.tenantId() == null
                    || !command.tenantId().equals(tenantId)
                    || command.pullTaskId() == null
                    || command.groupExecutionId() == null
                    || command.actionId() == null
                    || command.actor() == null
                    || command.actor().armadaAccountId() <= 0
                    || isBlank(command.actor().protocolAccountId())
                    || isBlank(command.actor().wsPhone())) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "普通拉群管理员设置协议命令缺少必要字段或租户不一致");
            }
        }
    }

    /** 校验普通拉群站台和料子批量入群命令。 */
    private void validatePullTaskBatchAddCommands(
            List<ProtocolPullTaskBatchAddCommandRequest> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "普通拉群批量入群协议命令不能为空");
        }
        if (commands.size() > MAX_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "普通拉群批量入群协议命令不能超过 " + MAX_COMMANDS_PER_BATCH + " 条");
        }
        Long tenantId = TenantContext.get();
        for (ProtocolPullTaskBatchAddCommandRequest command : commands) {
            if (command == null
                    || command.tenantId() == null
                    || !command.tenantId().equals(tenantId)
                    || command.pullTaskId() == null
                    || command.groupExecutionId() == null
                    || command.pullCallId() == null
                    || command.actor() == null
                    || command.actor().armadaAccountId() <= 0
                    || isBlank(command.actor().protocolAccountId())
                    || isBlank(command.actor().wsPhone())) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "普通拉群批量入群协议命令缺少必要字段或租户不一致");
            }
        }
    }

    /** 校验普通拉群料子提权命令。 */
    private void validatePullTaskMaterialAdminCommands(
            List<ProtocolPullTaskMaterialAdminCommandRequest> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "普通拉群料子提权协议命令不能为空");
        }
        if (commands.size() > MAX_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "普通拉群料子提权协议命令不能超过 " + MAX_COMMANDS_PER_BATCH + " 条");
        }
        Long tenantId = TenantContext.get();
        for (ProtocolPullTaskMaterialAdminCommandRequest command : commands) {
            if (command == null
                    || command.tenantId() == null
                    || !command.tenantId().equals(tenantId)
                    || command.pullTaskId() == null
                    || command.groupExecutionId() == null
                    || command.materialId() == null
                    || command.managerGroupAccountId() == null
                    || command.actor() == null
                    || command.actor().armadaAccountId() <= 0
                    || isBlank(command.actor().protocolAccountId())
                    || isBlank(command.actor().wsPhone())) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "普通拉群料子提权协议命令缺少必要字段或租户不一致");
            }
        }
    }

    /** 校验普通拉群成员查询命令和当前租户。 */
    private void validatePullTaskMemberQueryCommands(
            List<ProtocolPullTaskMemberQueryCommandRequest> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "普通拉群成员查询命令不能为空");
        }
        if (commands.size() > MAX_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "普通拉群成员查询命令不能超过 " + MAX_COMMANDS_PER_BATCH + " 条");
        }
        Long tenantId = TenantContext.get();
        for (ProtocolPullTaskMemberQueryCommandRequest command : commands) {
            if (command == null
                    || command.tenantId() == null
                    || !command.tenantId().equals(tenantId)
                    || command.pullTaskId() == null || command.pullTaskId() <= 0
                    || command.groupExecutionId() == null || command.groupExecutionId() <= 0
                    || command.queryId() == null || command.queryId() <= 0
                    || command.actor() == null
                    || command.actor().armadaAccountId() <= 0
                    || isBlank(command.actor().protocolAccountId())
                    || isBlank(command.actor().wsPhone())) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "普通拉群成员查询命令缺少必要字段或租户不一致");
            }
        }
    }

    /** 校验群主退群链路的动作、路由账号和当前租户。 */
    private void validatePullTaskCreatorLeaveCommands(
            List<ProtocolPullTaskCreatorLeaveCommandRequest> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "群主退群协议命令不能为空");
        }
        if (commands.size() > MAX_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "群主退群协议命令不能超过 " + MAX_COMMANDS_PER_BATCH + " 条");
        }
        Long tenantId = TenantContext.get();
        for (ProtocolPullTaskCreatorLeaveCommandRequest command : commands) {
            if (command == null
                    || command.tenantId() == null
                    || !command.tenantId().equals(tenantId)
                    || command.pullTaskId() == null || command.pullTaskId() <= 0
                    || command.groupExecutionId() == null || command.groupExecutionId() <= 0
                    || command.actionId() == null || command.actionId() <= 0
                    || command.action() == null
                    || command.actor() == null
                    || command.actor().armadaAccountId() == null
                    || command.actor().armadaAccountId() <= 0
                    || command.actor().backend() == null
                    || isBlank(command.actor().protocolAccountId())
                    || isBlank(command.actor().wsPhone())) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "群主退群协议命令缺少必要字段或租户不一致");
            }
        }
    }

    private void validateMessageCommands(List<ProtocolMessageOutboxCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销消息发送命令不能为空");
        }
        if (commands.size() > MAX_COMMANDS_PER_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "营销消息发送命令不能超过 " + MAX_COMMANDS_PER_BATCH + " 条");
        }
        for (ProtocolMessageOutboxCommand outboxCommand : commands) {
            validateMessageCommand(outboxCommand);
        }
    }

    private void validateMessageCommand(ProtocolMessageOutboxCommand outboxCommand) {
        if (outboxCommand == null
                || outboxCommand.command() == null
                || outboxCommand.command().account() == null
                || outboxCommand.command().target() == null
                || outboxCommand.command().payload() == null
                || outboxCommand.command().correlation() == null
                || isBlank(outboxCommand.command().commandId())
                || isBlank(outboxCommand.command().account().protocolAccountId())
                || isBlank(outboxCommand.command().target().jid())
                || outboxCommand.command().target().kind() == null
                || outboxCommand.backend() == null
                || outboxCommand.backend() != outboxCommand.command().account().backend()
                || isBlank(outboxCommand.kafkaTopic())
                || isBlank(outboxCommand.kafkaKey())
                || outboxCommand.payload() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销消息发送命令缺少必要字段");
        }
        com.armada.platform.protocol.model.command.MessageSendCommand.MessageCorrelation correlation =
                outboxCommand.command().correlation();
        if (correlation.tenantId() == null || isBlank(correlation.source())) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销消息发送命令缺少关联字段");
        }
        if (correlation.groupCreation() != null) {
            if (correlation.groupCreation().taskId() == null
                    || correlation.groupCreation().itemId() == null
                    || correlation.marketing() != null
                    || correlation.historicalGroup() != null
                    || correlation.contactTask() != null
                    || correlation.feedTask() != null
                    || correlation.hyperlink() != null
                    || outboxCommand.command().target().kind()
                        != MessageSendCommand.TargetKind.GROUP) {
                throw new BusinessException(ErrorCode.VALIDATION, "建群营销消息发送命令缺少执行项字段");
            }
            return;
        }
        if (correlation.historicalGroup() != null) {
            if (!SOURCE_HISTORICAL_GROUP_PULL.equals(correlation.source())
                    || correlation.historicalGroup().executionId() == null
                    || correlation.historicalGroup().memberId() == null
                    || correlation.marketing() != null
                    || correlation.contactTask() != null
                    || correlation.feedTask() != null
                    || correlation.hyperlink() != null
                    || outboxCommand.command().target().kind()
                        != MessageSendCommand.TargetKind.GROUP) {
                throw new BusinessException(ErrorCode.VALIDATION, "历史群营销消息发送命令缺少执行成员字段");
            }
            return;
        }
        if (SOURCE_HISTORICAL_GROUP_PULL.equals(correlation.source())) {
            throw new BusinessException(ErrorCode.VALIDATION, "历史群营销消息发送命令缺少执行成员字段");
        }
        if (correlation.hyperlink() != null) {
            if (!SOURCE_HYPERLINK_TASK.equals(correlation.source())
                    || correlation.hyperlink().taskId() == null
                    || correlation.hyperlink().recipientId() == null
                    || correlation.marketing() != null
                    || correlation.groupCreation() != null
                    || correlation.historicalGroup() != null
                    || correlation.contactTask() != null
                    || correlation.feedTask() != null
                    || outboxCommand.command().target().kind()
                        != MessageSendCommand.TargetKind.PRIVATE) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "超链任务消息命令缺少唯一 recipient 关联");
            }
            return;
        }
        if (SOURCE_HYPERLINK_TASK.equals(correlation.source())) {
            throw new BusinessException(ErrorCode.VALIDATION, "超链任务消息命令缺少唯一 recipient 关联");
        }
        if (correlation.contactTask() != null) {
            if (!SOURCE_CONTACT_TASK.equals(correlation.source())
                    || correlation.contactTask().taskId() == null
                    || correlation.contactTask().taskAccountId() == null
                    || correlation.contactTask().recipientId() == null
                    || correlation.contactTask().roundNo() == null
                    || correlation.marketing() != null
                    || correlation.groupCreation() != null
                    || correlation.historicalGroup() != null
                    || correlation.feedTask() != null
                    || correlation.hyperlink() != null
                    || outboxCommand.command().target().kind()
                        != MessageSendCommand.TargetKind.PRIVATE) {
                throw new BusinessException(ErrorCode.VALIDATION, "通讯录任务消息命令缺少收件人关联");
            }
            return;
        }
        if (SOURCE_CONTACT_TASK.equals(correlation.source())) {
            throw new BusinessException(ErrorCode.VALIDATION, "通讯录任务消息命令缺少收件人关联");
        }
        if (correlation.feedTask() != null) {
            if (!SOURCE_FEED_TASK.equals(correlation.source())
                    || correlation.feedTask().taskId() == null
                    || correlation.feedTask().taskAccountId() == null
                    || correlation.feedTask().roundNo() == null
                    || correlation.marketing() != null
                    || correlation.groupCreation() != null
                    || correlation.historicalGroup() != null
                    || correlation.contactTask() != null
                    || correlation.hyperlink() != null
                    || outboxCommand.command().target().kind()
                        != MessageSendCommand.TargetKind.STATUS
                    || outboxCommand.command().payload().type() != com.armada.platform.protocol.model.enums.MessageType.STATUS
                    || outboxCommand.command().target().statusJidList() == null
                    || outboxCommand.command().target().statusJidList().isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION, "动态发布任务消息命令缺少账号关联");
            }
            return;
        }
        if (SOURCE_FEED_TASK.equals(correlation.source())) {
            throw new BusinessException(ErrorCode.VALIDATION, "动态发布任务消息命令缺少账号关联");
        }
        if (correlation.marketing() == null
                || correlation.marketing().taskId() == null
                || correlation.marketing().targetId() == null
                || correlation.marketing().attemptId() == null
                || correlation.marketing().roundNo() == null
                || correlation.groupCreation() != null
                || correlation.historicalGroup() != null
                || correlation.contactTask() != null
                || correlation.feedTask() != null) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销消息发送命令缺少营销回写字段");
        }
    }

    /**
     * 判断文本是否为空白。
     *
     * @param value 待判断文本
     * @return {@code true} 表示文本为 null、空字符串或全空白字符
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String onlineCommandTopic(ProtocolBackend protocolBackend) {
        return protocolBackend == ProtocolBackend.ANDROID
                ? androidCommandProperties.getLifecycleTopic()
                : accountCommandProperties.getTopic();
    }

    /**
     * 群列表同步命令的 topic 分流。
     *
     * <p>不能复用 {@link #onlineCommandTopic}：Web 上线命令走 account topic，而群列表同步一直走
     * master topic 由 master 按 owner 路由 worker，两者不是同一个 topic。安卓侧直投生命周期 topic，
     * 由持号的 fleet 节点执行。</p>
     */
    private String accountGroupSyncCommandTopic(ProtocolBackend protocolBackend) {
        return protocolBackend == ProtocolBackend.ANDROID
                ? androidCommandProperties.getLifecycleTopic()
                : masterCommandProperties.getTopic();
    }

    private String offlineCommandTopic(ProtocolBackend protocolBackend) {
        return protocolBackend == ProtocolBackend.ANDROID
                ? androidCommandProperties.getLifecycleTopic()
                : masterCommandProperties.getTopic();
    }

    private record ProtocolOnlineCommandPayload(
            Long accountId,
            String protocolAccountId,
            CredentialFormat credentialFormat,
            Long proxyId,
            String source,
            String onlineAttemptId,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            String previousOnlineAttemptId,
            ProtocolBackend protocolBackend,
            boolean isBusiness,
            Integer declaredAccountType,
            boolean detectAccountType,
            Integer deviceOs
    ) {
    }

    private record ProtocolOfflineCommandPayload(
            Long accountId,
            String protocolAccountId,
            String source,
            ProtocolBackend protocolBackend
    ) {
    }

    private record ProtocolGroupHealthCheckCommandPayload(
            Long tenantId,
            Long groupLinkId,
            String groupJid,
            Long accountId,
            String protocolAccountId,
            String source
    ) {
    }

    private record ProtocolAccountGroupSyncCommandPayload(
            Long tenantId,
            Long accountId,
            String protocolAccountId,
            String source
    ) {
    }

}
