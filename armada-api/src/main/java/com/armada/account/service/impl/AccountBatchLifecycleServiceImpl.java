package com.armada.account.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.dto.AccountBatchPreviewDTO;
import com.armada.account.model.dto.AccountBatchQueryDTO;
import com.armada.account.model.dto.AccountBatchTargetQuery;
import com.armada.account.model.dto.AccountQuery;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.enums.AccountBatchOperation;
import com.armada.account.model.enums.AccountBatchScope;
import com.armada.account.model.enums.AccountBatchSkipReason;
import com.armada.account.model.vo.AccountBatchCommandResultVO;
import com.armada.account.model.vo.AccountBatchOnlineItemVO;
import com.armada.account.model.vo.AccountBatchOnlineRemoteRouteVO;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.model.vo.AccountBatchPreviewRow;
import com.armada.account.model.vo.AccountBatchPreviewVO;
import com.armada.account.model.vo.AccountBatchTargetRow;
import com.armada.account.service.AccountBatchLifecycleService;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账号批量生命周期编排服务实现。
 *
 * <p>外部 ID 请求最多接收 2,000 个账号。登录按 500 个、离线按 1,000 个拆分，避免扩大代理
 * 分配事务和协议 outbox 的安全批次。筛选范围由 Mapper 在当前租户内使用稳定 ID 游标扫描，
 * 不依赖前端当前页数据。</p>
 *
 * <p>本服务不创建覆盖整个外部请求的事务；每个内部命令批次独立提交并单独汇总错误，
 * 因此前一批已受理命令不会因后一批失败回滚。返回的 accepted 只代表命令已写入 outbox，
 * 不代表账号已经达到最终 ONLINE/OFFLINE 状态。</p>
 */
@Service
public class AccountBatchLifecycleServiceImpl implements AccountBatchLifecycleService {

    /** 账号批量生命周期编排日志，不记录凭据正文或代理密码。 */
    private static final Logger log = LoggerFactory.getLogger(AccountBatchLifecycleServiceImpl.class);

    /** 明确 ID 批量接口单次允许接收的最大账号数。 */
    private static final int ID_REQUEST_MAX = 2_000;

    /** 登录内部批次上限，与代理批量分配的 500 账号事务边界保持一致。 */
    private static final int ONLINE_CHUNK_SIZE = 500;

    /** 离线内部批次上限，与现有账号命令服务的 1,000 账号容量保持一致。 */
    private static final int OFFLINE_CHUNK_SIZE = 1_000;

    /** 单次响应最多保留的内部批次错误摘要数，防止查询范围过大时响应无界增长。 */
    private static final int MAX_BATCH_ERROR_MESSAGES = 20;

    /** 单条内部批次错误摘要最大字符数。 */
    private static final int MAX_BATCH_ERROR_LENGTH = 200;

    /** 账号批量目标预估与稳定游标扫描的数据访问器。 */
    private final AccountMapper accountMapper;

    /** 现有账号上线、下线 outbox 命令服务；本类只负责分片调用和汇总。 */
    private final AccountOnlineCommandService commandService;

    /**
     * 创建账号批量生命周期编排服务。
     *
     * @param accountMapper 账号批量目标数据访问器
     * @param commandService 账号上线、下线 outbox 命令服务
     */
    public AccountBatchLifecycleServiceImpl(AccountMapper accountMapper,
                                            AccountOnlineCommandService commandService) {
        this.accountMapper = accountMapper;
        this.commandService = commandService;
    }

    /**
     * 预估明确 ID 或账号列表筛选范围内的匹配、可执行和跳过账号数量。
     *
     * <p>预估使用与账号列表一致的 SQL 筛选，但不锁定目标。用户确认后执行阶段会重新查询，
     * 因此最终数量以执行汇总为准。IDS 和 QUERY 必须显式二选一，禁止把空 ID 隐式解释为全量。</p>
     *
     * @param request 操作类型、范围以及对应的 ID 或筛选条件
     * @return 后端事实口径下的匹配、预计执行、跳过及跳过原因数量
     * @throws BusinessException 当操作、范围、ID 参数不合法时抛出
     */
    @Override
    public AccountBatchPreviewVO preview(AccountBatchPreviewDTO request) {
        validatePreviewHeader(request);
        AccountBatchPreviewRow row;
        if (request.scope() == AccountBatchScope.IDS) {
            if (request.ids() == null || request.ids().isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION, "IDS 预估必须提供账号 ID");
            }
            if (request.query() != null) {
                throw new BusinessException(ErrorCode.VALIDATION, "IDS 预估不能同时提供查询条件");
            }
            row = accountMapper.previewBatchTargetsByIds(normalizeIds(request.ids()));
        } else {
            if (request.ids() != null && !request.ids().isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION, "QUERY 预估不能同时提供账号 ID");
            }
            row = accountMapper.previewBatchTargetsByQuery(normalizeQuery(request.query()).toAccountQuery());
        }
        AccountBatchPreviewVO result = request.operation() == AccountBatchOperation.OFFLINE
                ? offlinePreview(row.getMatched())
                : onlinePreview(row);
        log.info("账号批量预估完成 operation={} scope={} matched={} executable={} skipped={}",
                request.operation(), request.scope(), result.matched(), result.executable(), result.skipped());
        return result;
    }

    /**
     * 对明确选择的账号执行批量登录编排。
     *
     * <p>封禁、解绑、抢登中和缺凭据账号在 Armada 内跳过；登录态不参与跳过，在线账号仍会进入
     * 协议命令，由协议层执行现有幂等判断。最多接收 2,000 个 ID，内部按 500 个账号分片。</p>
     *
     * @param ids 当前租户明确选择的账号 ID
     * @return 所有登录内部批次的受理、跳过和失败汇总
     * @throws BusinessException 当 ID 为空、重复、超过上限或目标不属于当前租户时抛出
     */
    @Override
    public AccountBatchCommandResultVO onlineByIds(List<Long> ids) {
        return executeByIds(ids, AccountBatchOperation.ONLINE);
    }

    /**
     * 对明确选择的账号执行批量离线编排。
     *
     * <p>离线命令不读取凭据，也不按账号生命周期状态跳过。最多接收 2,000 个 ID，
     * 内部按 1,000 个账号分片。</p>
     *
     * @param ids 当前租户明确选择的账号 ID
     * @return 所有离线内部批次的受理和失败汇总
     * @throws BusinessException 当 ID 为空、重复、超过上限或目标不属于当前租户时抛出
     */
    @Override
    public AccountBatchCommandResultVO offlineByIds(List<Long> ids) {
        return executeByIds(ids, AccountBatchOperation.OFFLINE);
    }

    /**
     * 按账号列表已生效筛选条件执行全部匹配账号的批量登录编排。
     *
     * <p>目标由后端在当前租户内按稳定 ID 游标扫描，不读取分页参数。登录跳过规则与
     * {@link #onlineByIds(List)} 保持一致，内部命令批次失败后继续扫描后续账号。</p>
     *
     * @param query 已生效且不含分页语义的筛选条件；null 等价于空条件
     * @return 全部匹配账号的受理、跳过和失败汇总，不返回无界单账号明细
     * @throws BusinessException 当稳定 ID 游标无法继续向前推进时抛出
     */
    @Override
    public AccountBatchCommandResultVO onlineByQuery(AccountBatchQueryDTO query) {
        return executeByQuery(query, AccountBatchOperation.ONLINE);
    }

    /**
     * 按账号列表已生效筛选条件执行全部匹配账号的批量离线编排。
     *
     * <p>目标由后端在当前租户内按稳定 ID 游标扫描，不读取分页参数。离线不套用登录跳过规则，
     * 内部命令批次失败后继续扫描后续账号。</p>
     *
     * @param query 已生效且不含分页语义的筛选条件；null 等价于空条件
     * @return 全部匹配账号的受理和失败汇总，不返回无界单账号明细
     * @throws BusinessException 当稳定 ID 游标无法继续向前推进时抛出
     */
    @Override
    public AccountBatchCommandResultVO offlineByQuery(AccountBatchQueryDTO query) {
        return executeByQuery(query, AccountBatchOperation.OFFLINE);
    }

    private void validatePreviewHeader(AccountBatchPreviewDTO request) {
        if (request == null || request.operation() == null || request.scope() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "批量预估操作和范围不能为空");
        }
    }

    private AccountBatchPreviewVO onlinePreview(AccountBatchPreviewRow row) {
        Map<String, Long> skipReasons = new LinkedHashMap<>();
        putPositive(skipReasons, AccountBatchSkipReason.BANNED, row.getBanned());
        putPositive(skipReasons, AccountBatchSkipReason.UNBOUND, row.getUnbound());
        putPositive(skipReasons, AccountBatchSkipReason.TAKING_OVER, row.getTakingOver());
        putPositive(skipReasons, AccountBatchSkipReason.MISSING_CREDENTIAL, row.getMissingCredential());
        long skipped = skipReasons.values().stream().mapToLong(Long::longValue).sum();
        long executable = Math.max(0L, row.getMatched() - skipped);
        return new AccountBatchPreviewVO(
                row.getMatched(), executable, skipped,
                Collections.unmodifiableMap(skipReasons));
    }

    private void putPositive(
            Map<String, Long> result,
            AccountBatchSkipReason reason,
            long count) {
        if (count > 0) {
            result.put(reason.name(), count);
        }
    }

    private AccountBatchPreviewVO offlinePreview(long matched) {
        return new AccountBatchPreviewVO(matched, matched, 0L, Map.of());
    }

    /**
     * 按已生效筛选条件扫描并执行全部匹配账号。
     *
     * <p>使用单调递增的账号 ID 作为游标，避免 offset 在执行期间因数据变化造成重复或漏页。
     * 每次只加载一个安全命令批次所需的最小字段；命令批次失败由 {@link #executeChunk}
     * 计入汇总并继续，Mapper 查询失败则直接终止，因为此时无法可靠确定后续游标位置。</p>
     *
     * @param inputQuery 不含分页语义的账号列表筛选条件；null 表示全部账号
     * @param operation 批量登录或批量离线
     * @return 全部已扫描目标的受理、跳过和失败汇总
     * @throws BusinessException 当 Mapper 返回的账号 ID 游标未向前推进时抛出
     */
    private AccountBatchCommandResultVO executeByQuery(
            AccountBatchQueryDTO inputQuery,
            AccountBatchOperation operation) {
        AccountQuery filters = normalizeQuery(inputQuery).toAccountQuery();
        int scanSize = operation == AccountBatchOperation.ONLINE
                ? ONLINE_CHUNK_SIZE
                : OFFLINE_CHUNK_SIZE;
        // ID 游标只向前推进；不使用 offset，避免处理期间数据变化导致后续分页位置漂移。
        long afterId = 0L;
        int cursorIndex = 0;
        BatchAccumulator accumulator = new BatchAccumulator(0, false);
        log.info("账号批量编排开始 operation={} scope=QUERY", operation);
        while (true) {
            List<AccountBatchTargetRow> targets = accountMapper.selectBatchTargetsAfterId(
                    AccountBatchTargetQuery.from(filters, afterId, scanSize));
            if (targets.isEmpty()) {
                break;
            }
            cursorIndex++;
            long nextAfterId = targets.get(targets.size() - 1).getId();
            if (nextAfterId <= afterId) {
                throw new BusinessException(ErrorCode.CONFLICT, "批量账号游标未向前推进");
            }
            afterId = nextAfterId;
            accumulator.addRequested(targets.size());
            List<Long> executableIds = operation == AccountBatchOperation.ONLINE
                    ? classifyOnlineTargets(targets, accumulator)
                    : targetIds(targets);
            executeChunks(executableIds, operation, accumulator);
            log.info("账号批量游标批次已扫描 operation={} cursorIndex={} scanned={} afterId={}",
                    operation, cursorIndex, targets.size(), afterId);
            if (targets.size() < scanSize) {
                break;
            }
        }
        AccountBatchCommandResultVO result = accumulator.toVO();
        log.info("账号批量编排完成 operation={} scope=QUERY requested={} submitted={} accepted={} skipped={} failed={}",
                operation, result.requested(), result.submitted(), result.accepted(), result.skipped(), result.failed());
        return result;
    }

    private AccountBatchQueryDTO normalizeQuery(AccountBatchQueryDTO query) {
        return query == null
                ? new AccountBatchQueryDTO(
                        null, null, null, null, null, null, null, null,
                        null, null, null, null, null)
                : query;
    }

    /**
     * 校验并执行明确 ID 范围的批量登录或离线。
     *
     * <p>执行前必须确认所有 ID 都是当前租户的活跃账号，避免部分匹配时静默缩小用户勾选范围。
     * ID 请求最多 2,000 个，进入命令服务前仍按操作类型拆成安全小批次。</p>
     *
     * @param inputIds 用户明确选择的账号 ID
     * @param operation 批量登录或批量离线
     * @return 所有内部命令批次的聚合结果
     * @throws BusinessException 当 ID 参数不合法或存在非当前租户活跃账号时抛出
     */
    private AccountBatchCommandResultVO executeByIds(List<Long> inputIds, AccountBatchOperation operation) {
        List<Long> ids = normalizeIds(inputIds);
        log.info("账号批量编排开始 operation={} scope=IDS requested={}", operation, ids.size());
        List<AccountBatchTargetRow> targets = accountMapper.selectBatchTargetsByIds(ids);
        if (targets.size() != ids.size()) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "部分账号不存在、已删除或不属于当前租户: requested=" + ids.size()
                            + " matched=" + targets.size());
        }

        BatchAccumulator accumulator = new BatchAccumulator(ids.size(), true);
        List<Long> executableIds = operation == AccountBatchOperation.ONLINE
                ? classifyOnlineTargets(targets, accumulator)
                : targetIds(targets);
        executeChunks(executableIds, operation, accumulator);
        AccountBatchCommandResultVO result = accumulator.toVO();
        log.info("账号批量编排完成 operation={} scope=IDS requested={} submitted={} accepted={} skipped={} failed={}",
                operation, result.requested(), result.submitted(), result.accepted(), result.skipped(), result.failed());
        return result;
    }

    private List<Long> normalizeIds(List<Long> inputIds) {
        if (inputIds == null || inputIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 列表不能为空");
        }
        if (inputIds.size() > ID_REQUEST_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION, "批量账号操作一次最多 2000 个账号");
        }
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (Long id : inputIds) {
            if (id == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能为空");
            }
            if (!uniqueIds.add(id)) {
                throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能重复: " + id);
            }
        }
        return List.copyOf(uniqueIds);
    }

    private List<Long> classifyOnlineTargets(
            List<AccountBatchTargetRow> targets,
            BatchAccumulator accumulator) {
        List<Long> executableIds = new ArrayList<>(targets.size());
        for (AccountBatchTargetRow target : targets) {
            AccountBatchSkipReason skipReason = onlineSkipReason(target);
            if (skipReason == null) {
                executableIds.add(target.getId());
            } else {
                accumulator.addSkip(skipReason);
            }
        }
        return executableIds;
    }

    private AccountBatchSkipReason onlineSkipReason(AccountBatchTargetRow target) {
        Integer accountState = target.getAccountState();
        if (Integer.valueOf(AccountStateCode.BANNED).equals(accountState)) {
            return AccountBatchSkipReason.BANNED;
        }
        if (Integer.valueOf(AccountStateCode.UNBOUND).equals(accountState)) {
            return AccountBatchSkipReason.UNBOUND;
        }
        if (Integer.valueOf(AccountStateCode.TAKING_OVER).equals(accountState)) {
            return AccountBatchSkipReason.TAKING_OVER;
        }
        if (!target.isCredentialPresent()) {
            return AccountBatchSkipReason.MISSING_CREDENTIAL;
        }
        return null;
    }

    private List<Long> targetIds(List<AccountBatchTargetRow> targets) {
        return targets.stream().map(AccountBatchTargetRow::getId).toList();
    }

    /**
     * 按操作对应的安全容量拆分账号并依次提交命令。
     *
     * @param executableIds 已通过登录跳过规则或离线目标校验的账号 ID
     * @param operation 批量登录或批量离线
     * @param accumulator 当前外部请求的结果聚合器
     */
    private void executeChunks(
            List<Long> executableIds,
            AccountBatchOperation operation,
            BatchAccumulator accumulator) {
        int chunkSize = operation == AccountBatchOperation.ONLINE
                ? ONLINE_CHUNK_SIZE
                : OFFLINE_CHUNK_SIZE;
        for (int start = 0; start < executableIds.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, executableIds.size());
            int chunkIndex = accumulator.nextChunkIndex();
            executeChunk(executableIds.subList(start, end), operation, chunkIndex, accumulator);
        }
    }

    /**
     * 独立提交一个内部命令批次并累加受理结果。
     *
     * <p>单批运行时异常只影响该批账号：整批计为失败并保留有界错误摘要，随后继续处理后续批次。
     * 不重新抛出异常，避免已受理的前序 outbox 命令被外层误判为整体回滚。</p>
     *
     * @param chunk 当前安全批次的账号 ID
     * @param operation 批量登录或批量离线
     * @param chunkIndex 当前外部请求内连续递增的批次序号
     * @param accumulator 当前外部请求的结果聚合器
     */
    private void executeChunk(
            List<Long> chunk,
            AccountBatchOperation operation,
            int chunkIndex,
            BatchAccumulator accumulator) {
        accumulator.submitted += chunk.size();
        try {
            AccountBatchOnlineVO result = operation == AccountBatchOperation.ONLINE
                    ? commandService.onlineBatch(chunk)
                    : commandService.offlineBatch(chunk);
            accumulator.accept(result, chunk.size());
            log.info("账号批量内部批次已处理 operation={} chunkIndex={} requested={} accepted={}",
                    operation, chunkIndex, chunk.size(), result.accepted());
        } catch (RuntimeException exception) {
            // 前序批次可能已经独立写入 outbox；这里只记录本批失败，不能回滚或中断后续批次。
            accumulator.failed += chunk.size();
            String error = safeBatchError(exception);
            accumulator.addBatchError(error);
            log.warn("账号批量内部批次失败 operation={} chunkIndex={} requested={} errorType={} error={}",
                    operation, chunkIndex, chunk.size(), exception.getClass().getSimpleName(), error);
        }
    }

    /**
     * 将内部异常转换为有界错误摘要。
     *
     * <p>这里只清理换行并限制长度，防止响应和日志无界增长；不把该处理描述为通用脱敏。
     * 调用链禁止把凭据正文、token 或代理密码放入异常消息。</p>
     *
     * @param exception 内部命令批次异常
     * @return 清理换行且不超过 200 个字符的错误摘要
     */
    private String safeBatchError(RuntimeException exception) {
        String message = exception.getMessage();
        String safeMessage = message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.replaceAll("[\\r\\n]+", " ");
        return safeMessage.length() <= MAX_BATCH_ERROR_LENGTH
                ? safeMessage
                : safeMessage.substring(0, MAX_BATCH_ERROR_LENGTH);
    }

    /**
     * 单次外部请求的内存聚合器，只保存计数和有界明细，不承担业务判断。
     */
    private static final class BatchAccumulator {

        /** 当前外部请求已匹配的账号总数。 */
        private int requested;

        /** 是否保留有界单账号结果；仅最多 2,000 个 ID 的接口启用。 */
        private final boolean includeDetails;

        /** 按互斥业务原因累计的登录跳过数量。 */
        private final EnumMap<AccountBatchSkipReason, Integer> skipReasons =
                new EnumMap<>(AccountBatchSkipReason.class);

        /** 最多保留 {@value #MAX_BATCH_ERROR_MESSAGES} 条内部批次错误摘要。 */
        private final List<String> batchErrors = new ArrayList<>();

        /** 明确 ID 接口返回的有界单账号命令结果。 */
        private final List<AccountBatchOnlineItemVO> results = new ArrayList<>();

        /** 明确 ID 接口返回的有界远端路由结果。 */
        private final List<AccountBatchOnlineRemoteRouteVO> remoteRoutes = new ArrayList<>();

        /** 已进入现有命令服务的账号数，包含命令服务未受理或抛异常的账号。 */
        private int submitted;

        /** 已成功写入协议命令 outbox 的账号数。 */
        private int accepted;

        /** 下层兼容结果中的超时账号数。 */
        private int timeout;

        /** 下层兼容结果中的代理不足账号数。 */
        private int proxyRequired;

        /** 下层兼容结果中的远端路由账号数。 */
        private int remote;

        /** 所有内部命令批次累计耗时，单位毫秒。 */
        private long elapsedMs;

        /** 因登录业务规则未进入命令服务的账号数。 */
        private int skipped;

        /** 未被 outbox 受理或内部命令批次异常的账号数。 */
        private int failed;

        /** 当前外部请求内连续递增的内部命令批次序号。 */
        private int chunkSequence;

        private BatchAccumulator(int requested, boolean includeDetails) {
            this.requested = requested;
            this.includeDetails = includeDetails;
        }

        private void addRequested(int count) {
            requested += count;
        }

        private void addSkip(AccountBatchSkipReason reason) {
            skipped++;
            skipReasons.merge(reason, 1, Integer::sum);
        }

        private int nextChunkIndex() {
            return ++chunkSequence;
        }

        private void accept(AccountBatchOnlineVO value, int chunkSize) {
            accepted += value.accepted();
            timeout += value.timeout();
            proxyRequired += value.proxyRequired();
            remote += value.remote();
            elapsedMs += value.elapsedMs();
            int unaccepted = Math.max(0, chunkSize - value.accepted());
            failed += unaccepted;
            if (unaccepted > 0) {
                addBatchError("批次存在 " + unaccepted + " 个未受理账号");
            }
            if (includeDetails) {
                results.addAll(value.results());
                remoteRoutes.addAll(value.remoteRoutes());
            }
        }

        private void addBatchError(String message) {
            if (batchErrors.size() < MAX_BATCH_ERROR_MESSAGES) {
                batchErrors.add(message);
            }
        }

        private AccountBatchCommandResultVO toVO() {
            Map<String, Integer> skipReasonCounts = new LinkedHashMap<>();
            for (AccountBatchSkipReason reason : AccountBatchSkipReason.values()) {
                Integer count = skipReasons.get(reason);
                if (count != null && count > 0) {
                    skipReasonCounts.put(reason.name(), count);
                }
            }
            return new AccountBatchCommandResultVO(
                    requested,
                    submitted,
                    accepted,
                    timeout,
                    proxyRequired,
                    failed,
                    remote,
                    elapsedMs,
                    skipped,
                    failed,
                    Collections.unmodifiableMap(skipReasonCounts),
                    List.copyOf(batchErrors),
                    includeDetails ? List.copyOf(results) : List.of(),
                    includeDetails ? List.copyOf(remoteRoutes) : List.of());
        }
    }
}
