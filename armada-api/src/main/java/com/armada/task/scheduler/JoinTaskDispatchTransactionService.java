package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolGroupJoinCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.model.enums.JoinTaskFailureReason;
import com.armada.task.service.JoinTaskIntervalPolicy;
import com.armada.task.service.JoinTaskInviteCodeParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在单租户短事务内把到期进群明细转换为协议 outbox 命令。
 *
 * <p>事务同时完成三件事：复核并锁定 WAITING 明细、写入 PENDING outbox、把明细切换为 SUBMITTED。
 * 三者必须原子提交，否则可能出现“协议已执行但业务仍可重复派发”或“业务等待一个不存在的命令”。
 * Kafka 发布由 outbox 的 after-commit 触发器负责，本服务不持锁等待网络。</p>
 *
 * <p>同账号串行由数据库状态保证：只有该账号当前被激活且到期的明细可被锁定；本次进入 SUBMITTED
 * 后，下一行要等结果状态机把当前行终结并设置随机 next_execute_at 才能参与扫描。不同账号可在同一
 * 事务批量入队，因此账号总数不受线程或固定 lane 数限制。</p>
 */
@Service
public class JoinTaskDispatchTransactionService {

    /** 进群明细持久化入口，负责加锁和派发状态迁移。 */
    private final JoinTaskResultMapper resultMapper;

    /** 进群任务持久化入口，用于读取间隔配置和刷新聚合计数。 */
    private final JoinTaskMapper taskMapper;

    /** 账号域查询边界，用于解析有效协议身份和 Web/Android 后端。 */
    private final AccountProtocolLookupService accountLookupService;

    /** 协议 outbox 应用服务，保证命令落库并在事务提交后触发 Kafka 发布。 */
    private final ProtocolCommandOutboxService outboxService;

    /** 群链接解析器，只允许协议层可接受的 WhatsApp 邀请码。 */
    private final JoinTaskInviteCodeParser inviteCodeParser;

    /** 同账号下一次执行时间策略，用于前置失败后继续推进 lane。 */
    private final JoinTaskIntervalPolicy intervalPolicy;

    /**
     * 创建单租户进群派发事务服务。
     *
     * @param resultMapper 进群明细 Mapper
     * @param taskMapper 进群任务 Mapper
     * @param accountLookupService 账号协议身份查询边界
     * @param outboxService 协议命令 outbox 服务
     * @param inviteCodeParser 群邀请码解析器
     * @param intervalPolicy 随机执行间隔策略
     */
    public JoinTaskDispatchTransactionService(JoinTaskResultMapper resultMapper,
                                              JoinTaskMapper taskMapper,
                                              AccountProtocolLookupService accountLookupService,
                                              ProtocolCommandOutboxService outboxService,
                                              JoinTaskInviteCodeParser inviteCodeParser,
                                              JoinTaskIntervalPolicy intervalPolicy) {
        this.resultMapper = resultMapper;
        this.taskMapper = taskMapper;
        this.accountLookupService = accountLookupService;
        this.outboxService = outboxService;
        this.inviteCodeParser = inviteCodeParser;
        this.intervalPolicy = intervalPolicy;
    }

    /**
     * 派发一个租户的一组预扫描候选。
     *
     * <p>预扫描结果只代表“可能到期”。本方法会在当前租户上下文中使用行锁再次检查状态和时间，
     * 已被其它实例处理的行会通过 SKIP LOCKED 或条件复核跳过。账号/链接在真正入队前失效时，当前行
     * 直接失败并按任务随机间隔激活同账号下一行；outbox 数量或状态更新不一致则回滚整组。</p>
     *
     * @param tenantId 候选所属租户 ID
     * @param resultIds 预扫描得到的进群明细 ID，必须全部属于该租户
     * @param now 本轮统一使用的当前时间（epoch 毫秒）
     * @return 本租户候选的锁定、入队和前置失败统计
     * @throws BusinessException outbox 受理数或明细状态与预期不一致时抛出并回滚
     * @throws ArithmeticException 尝试序号或时间计算溢出时抛出并回滚
     */
    @Transactional(rollbackFor = Exception.class)
    public JoinTaskDispatchStats dispatchTenant(Long tenantId, List<Long> resultIds, long now) {
        if (tenantId == null || resultIds == null || resultIds.isEmpty()) {
            return JoinTaskDispatchStats.empty();
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            // 跨租户预扫描不持锁；这里在租户上下文内二次校验并锁行，才拥有状态迁移资格。
            List<JoinTaskResult> rows = resultMapper.selectDueForUpdate(tenantId, resultIds, now);
            if (rows.isEmpty()) {
                return new JoinTaskDispatchStats(resultIds.size(), 0, 0, resultIds.size());
            }
            List<Long> accountIds = rows.stream().map(JoinTaskResult::getAccountId).distinct().toList();
            Map<Long, ProtocolAccountRef> refs = new LinkedHashMap<>();
            for (ProtocolAccountRef ref : accountLookupService.findActiveProtocolRefs(accountIds)) {
                refs.putIfAbsent(ref.armadaAccountId(), ref);
            }

            Map<Long, JoinTask> tasks = new LinkedHashMap<>();
            List<PreparedCommand> prepared = new java.util.ArrayList<>(rows.size());
            int skipped = 0;
            for (JoinTaskResult row : rows) {
                ProtocolAccountRef ref = refs.get(row.getAccountId());
                if (ref == null) {
                    terminateBeforeSubmit(row, JoinTaskFailureReason.ACCOUNT_NOT_FOUND.code(), tasks, now);
                    skipped++;
                    continue;
                }
                String inviteCode;
                try {
                    inviteCode = inviteCodeParser.parse(row.getLink());
                } catch (IllegalArgumentException ex) {
                    terminateBeforeSubmit(row, JoinTaskFailureReason.PROTOCOL_INVALID_GROUP_LINK.code(), tasks, now);
                    skipped++;
                    continue;
                }
                int attemptNo = Math.addExact(row.getAttemptNo(), 1);
                prepared.add(new PreparedCommand(row, new ProtocolGroupJoinCommandRequest(
                        tenantId,
                        row.getJoinTaskId(),
                        row.getId(),
                        row.getAccountId(),
                        ref.protocolAccountId(),
                        ref.wsPhone(),
                        ref.backend(),
                        inviteCode,
                        attemptNo,
                        ProtocolGroupJoinCommandRequest.SOURCE_JOIN_TASK)));
            }

            if (!prepared.isEmpty()) {
                List<ProtocolGroupJoinCommandRequest> commands = prepared.stream()
                        .map(PreparedCommand::command)
                        .toList();
                ProtocolCommandOutboxEnqueueResult result = outboxService.enqueueGroupJoinCommands(commands);
                if (result.inserted() != commands.size() || result.commandIds().size() != commands.size()) {
                    throw new BusinessException(ErrorCode.CONFLICT, "进群命令 outbox 受理数量不一致");
                }
                // outbox 与 SUBMITTED 必须处于同一事务；任一行状态竞争都回滚本批命令，禁止孤儿命令。
                for (int i = 0; i < prepared.size(); i++) {
                    PreparedCommand item = prepared.get(i);
                    if (resultMapper.markSubmitted(
                            item.row().getId(), result.commandIds().get(i), item.command().attemptNo(), now) != 1) {
                        throw new BusinessException(ErrorCode.CONFLICT, "进群任务明细状态已变化");
                    }
                }
            }
            return new JoinTaskDispatchStats(resultIds.size(), rows.size(), prepared.size(), skipped);
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    /**
     * 在命令写入前终结不可执行明细，并按随机间隔激活该账号下一行。
     *
     * @param row 已持锁的当前进群明细
     * @param reason 账号或链接前置校验失败原因码
     * @param tasks 当前事务内的任务缓存，避免同任务重复查询
     * @param now 当前时间（epoch 毫秒）
     * @throws BusinessException 任务消失或明细状态竞争时抛出并回滚
     */
    private void terminateBeforeSubmit(JoinTaskResult row,
                                       String reason,
                                       Map<Long, JoinTask> tasks,
                                       long now) {
        JoinTask task = tasks.computeIfAbsent(row.getJoinTaskId(), taskMapper::selectByTenantAndId);
        if (task == null || resultMapper.markTerminalFailure(row.getId(), reason, now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "进群任务明细状态已变化");
        }
        resultMapper.activateNextPending(
                row.getJoinTaskId(), row.getAccountId(), row.getId(), intervalPolicy.nextExecuteAt(task, now), now);
        taskMapper.refreshCounters(row.getJoinTaskId());
        taskMapper.markDoneWhenNoPending(row.getJoinTaskId(), now);
    }

    /**
     * 已完成前置校验、等待批量写入 outbox 的明细和命令对。
     *
     * @param row 已持锁的进群明细
     * @param command 与该明细一一对应的协议命令
     */
    private record PreparedCommand(JoinTaskResult row, ProtocolGroupJoinCommandRequest command) {
    }
}
