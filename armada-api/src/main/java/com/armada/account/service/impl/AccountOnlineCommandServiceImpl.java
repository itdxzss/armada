package com.armada.account.service.impl;

import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.command.AccountLifecycleCommandItem;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.entity.ImportFormat;
import com.armada.account.model.entity.ImportResult;
import com.armada.account.model.vo.AccountIpRegionRow;
import com.armada.account.model.vo.AccountBatchOnlineItemVO;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.model.vo.AccountOnlineVO;
import com.armada.account.service.AccountOnlineAttemptLogService;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.OnlineAttemptIdGenerator;
import com.armada.platform.country.service.CountryService;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.ProtocolOfflineCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolOnlineCommandRequest;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.BatchOnlineResultStatus;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.resource.service.IpProxyAccountAllocation;
import com.armada.resource.service.IpProxyAllocation;
import com.armada.resource.service.IpProxyAllocationRequest;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号生命周期命令应用服务实现。
 *
 * <p>本类是账号域的上线/下线入口编排:上线负责查凭据、分配代理并写 outbox;
 * 下线只校验账号并写 outbox。它不直接调用协议层;上线命令进入 outbox 后仅把登录态标记为待上线,
 * 不把"已受理"误写成"已在线"。</p>
 */
@Service
public class AccountOnlineCommandServiceImpl implements AccountOnlineCommandService {

    private static final Logger log = LoggerFactory.getLogger(AccountOnlineCommandServiceImpl.class);
    private static final int BATCH_COMMAND_MAX_SIZE = 1_000;
    private static final String OUTBOX_STATE_SOURCE = "OUTBOX";
    private static final String SOURCE_MANUAL_ONLINE = "manual_online";
    private static final String SOURCE_BATCH_ONLINE = "batch_online";
    private static final String SOURCE_BATCH_OFFLINE = "batch_offline";
    private static final String SOURCE_IP_DELETE_RELOGIN = "ip_delete_relogin";
    private static final String SOURCE_PROXY_FAILED_REONLINE = "proxy_failed_reonline";
    private static final String SOURCE_LOGIN_REPLACED_TAKEOVER = "login_replaced_takeover";
    private static final String TAKEOVER_SELECTION_MESSAGE = "当前所选账号存在非被抢登状态，请重新选择";
    private static final String TAKING_OVER_ONLINE_MESSAGE = "账号抢登中，请先离线";
    private static final String TAKEOVER_SKIPPED_SOURCE = "TAKEOVER_SKIPPED";
    private static final String PROXY_FAILED_REONLINE_SKIPPED_SOURCE = "PROXY_FAILED_REONLINE_SKIPPED";
    private static final String ALREADY_PENDING_SOURCE = "ALREADY_PENDING";
    private static final String ALREADY_ONLINE_SOURCE = "ALREADY_ONLINE";
    private static final int PROXY_SNAPSHOT_BATCH_SIZE = 100;
    private static final int TRUTH_IP_MAX_LENGTH = 45;
    private static final int PROXY_COUNTRY_MAX_LENGTH = 64;
    private static final int PROXY_SOURCE_MAX_LENGTH = 64;
    private static final String MIXED_REGION = "混合（不限国家）";
    private static final String IP_ALLOCATION_MODE_SMART = "smart";
    private static final String IP_ALLOCATION_MODE_MIXED = "mixed";
    private static final String IP_ALLOCATION_MODE_MIXED_COUNTRY_LEGACY = "mixed_country";

    private final AccountMapper accountMapper;
    private final AccountCredentialMapper credentialMapper;
    private final AccountStateMapper stateMapper;
    private final IpProxyService ipProxyService;
    private final CountryService countryService;
    private final ProtocolCommandOutboxService protocolCommandOutboxService;
    private final OnlineAttemptIdGenerator onlineAttemptIdGenerator;
    private final AccountOnlineAttemptLogService accountOnlineAttemptLogService;
    private final AccountTakeoverReonlineCooldown takeoverReonlineCooldown;

    /**
     * 创建账号上线编排服务。
     *
     * <p>这里保持构造器注入,便于单测替换账号、凭据、代理和 outbox 服务。</p>
     */
    public AccountOnlineCommandServiceImpl(AccountMapper accountMapper,
                                           AccountCredentialMapper credentialMapper,
                                           AccountStateMapper stateMapper,
                                           IpProxyService ipProxyService,
                                           CountryService countryService,
                                           ProtocolCommandOutboxService protocolCommandOutboxService,
                                           OnlineAttemptIdGenerator onlineAttemptIdGenerator,
                                           AccountOnlineAttemptLogService accountOnlineAttemptLogService,
                                           AccountTakeoverReonlineCooldown takeoverReonlineCooldown) {
        this.accountMapper = accountMapper;
        this.credentialMapper = credentialMapper;
        this.stateMapper = stateMapper;
        this.ipProxyService = ipProxyService;
        this.countryService = countryService;
        this.protocolCommandOutboxService = protocolCommandOutboxService;
        this.onlineAttemptIdGenerator = onlineAttemptIdGenerator;
        this.accountOnlineAttemptLogService = accountOnlineAttemptLogService;
        this.takeoverReonlineCooldown = takeoverReonlineCooldown;
    }

    /**
     * 自动分配代理并上线一个未软删账号。
     *
     * <p>该方法只完成"上线命令已可靠进入 outbox"的同步编排,不会把账号本地状态直接改成 ONLINE。
     * 真正是否在线以后续 Kafka 状态刷新为准。日志只记录运营定位需要的 ID、格式、长度和受理结果,
     * 不输出凭据 JSON、代理账号密码等敏感内容。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountOnlineVO online(Long accountId) {
        return onlineWithSource(accountId, SOURCE_MANUAL_ONLINE, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountOnlineVO reonlineAfterProxyFailure(Long accountId) {
        return reonlineAfterProxyFailure(accountId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountOnlineVO reonlineAfterProxyFailure(Long accountId, String failedOnlineAttemptId) {
        return reonlineAfterProxyFailure(accountId, failedOnlineAttemptId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountOnlineVO reonlineAfterProxyFailure(Long accountId,
                                                     String failedOnlineAttemptId,
                                                     Long failedProxyId) {
        return onlineWithSource(
                accountId, SOURCE_PROXY_FAILED_REONLINE, failedOnlineAttemptId, failedProxyId);
    }

    /**
     * 批量一键抢登被抢登账号。
     *
     * <p>该方法把“全部为被抢登且未禁言”的业务校验放在写 outbox 前,并在同一事务里把账号改为抢登中。
     * 后续是否真的在线仍由协议层状态事件回填。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountBatchOnlineVO takeoverBatch(List<Long> accountIds) {
        List<Long> ids = normalizeBatchAccountIds(accountIds);
        loadAccounts(ids);
        validateTakeoverStates(ids);
        long now = System.currentTimeMillis();
        updateDesiredLoginStateOrThrow(ids, AccountLoginStateCode.ONLINE, now);
        int updated = stateMapper.markTakingOverByAccountIds(
                ids,
                AccountStateCode.LOGIN_REPLACED,
                AccountStateCode.TAKING_OVER,
                now);
        if (updated != ids.size()) {
            throw new BusinessException(ErrorCode.VALIDATION, TAKEOVER_SELECTION_MESSAGE);
        }
        log.info("账号批量一键抢登开始 requested={}", ids.size());
        return enqueueOnlineBatch(
                ids,
                SOURCE_LOGIN_REPLACED_TAKEOVER,
                () -> ipProxyService.allocateOnlineEndpoints(allocationRequests(ids)));
    }

    /**
     * 抢登中账号自动续上线。
     *
     * <p>调用方通常是状态事件 side effect。这里再次读取账号状态,确保用户手动离线、禁言或状态变化后不会继续写 outbox。</p>
     */
    @Override
    public AccountOnlineVO reonlineForTakeover(Long accountId, String failedOnlineAttemptId, String source) {
        if (accountId == null) {
            return skippedTakeoverVO(null);
        }
        AccountState state = stateMapper.selectByAccountId(accountId);
        if (!isTakeoverEligible(state)) {
            log.info("抢登续上线跳过:账号不满足抢登中离线条件或已禁言 accountId={} source={}", accountId, source);
            return skippedTakeoverVO(accountId);
        }
        long now = System.currentTimeMillis();
        if (shouldApplyTakeoverCooldown(source) && !takeoverReonlineCooldown.tryAcquire(accountId, now)) {
            log.info("抢登续上线跳过:账号处于短窗口冷却 accountId={} source={}", accountId, source);
            return skippedTakeoverVO(accountId);
        }
        return onlineWithSource(
                accountId, requireText(source, "抢登续上线来源不能为空"), failedOnlineAttemptId, null);
    }

    private static boolean shouldApplyTakeoverCooldown(String source) {
        return !SOURCE_LOGIN_REPLACED_TAKEOVER.equals(source);
    }

    private AccountOnlineVO onlineWithSource(Long accountId,
                                             String source,
                                             String failedOnlineAttemptId,
                                             Long failedProxyId) {
        log.info("账号上线开始 accountId={}", accountId);

        // 1. 只允许未软删账号继续上线,并读取它对应的自托管凭据。
        Account account = loadAccount(accountId);
        if (SOURCE_MANUAL_ONLINE.equals(source)) {
            updateDesiredLoginStateOrThrow(
                    List.of(account.getId()), AccountLoginStateCode.ONLINE, System.currentTimeMillis());
        }
        Optional<AccountOnlineVO> idempotentResult = manualOnlineIdempotentResult(account, source);
        if (idempotentResult.isPresent()) {
            return idempotentResult.get();
        }
        AccountCredential credential = loadCredential(account.getId());
        CredentialFormat credentialFormat = toCredentialFormat(credential.getCredFormat());
        String protocolAccountId = requireText(account.getProtocolAccountId(), "协议账号 ID 为空");
        String onlineAttemptId = onlineAttemptIdGenerator.nextId();
        IpProxyAllocationRequest allocationRequest = allocationRequest(account.getId());

        // 用户手动上线先把 OFFLINE/未上报状态原子预占为 PENDING；并发请求更新不到行时不能释放旧代理。
        claimManualOnline(List.of(account.getId()), source);
        if (!claimProxyFailedReonline(account.getId(), source)) {
            log.info("账号代理失败重上线跳过,状态已变化 accountId={} failedProxyId={}",
                    account.getId(), failedProxyId);
            return skippedOnlineVO(account, PROXY_FAILED_REONLINE_SKIPPED_SOURCE);
        }

        // 2. resource 服务先释放该账号旧 IP,再按国家偏好乐观抢占一条空闲代理。
        IpProxyAllocation allocation = allocateOnlineEndpoint(allocationRequest, source, failedProxyId);

        ProtocolCommandOutboxEnqueueResult enqueueResult;
        try {
            // 3. outbox 只保存凭据格式和代理 ID;日志只打 JSON 长度,避免凭据泄露。
            log.info("账号上线写入 outbox 前准备 command accountId={} attemptId={} allocatedProxyId={} credentialFormat={} credentialLength={}",
                    account.getId(), onlineAttemptId, allocation.proxyId(), credentialFormat,
                    credentialLength(credential.getCredsJson()));

            ProtocolOnlineCommandRequest command = new ProtocolOnlineCommandRequest(
                    account.getId(),
                    protocolAccountId,
                    credentialFormat,
                    allocation.proxyId(),
                    source,
                    onlineAttemptId,
                    previousAttemptId(account.getId(), source, failedOnlineAttemptId),
                    ProtocolBackend.fromProtocolId(account.getProtocolId()),
                    isBusinessAccount(account));
            int snapshotUpdated = updateProxySnapshots(List.of(new IpProxyAccountAllocation(
                    account.getId(), allocation.proxyId(), allocation.endpoint(), allocation.proxySource())));
            if (snapshotUpdated != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "账号代理快照更新未命中: " + account.getId());
            }

            // 4. accepted 表示命令已进入本地 outbox,不等价于 WhatsApp 已经在线;最终状态等 Kafka 异步回填。
            enqueueResult = protocolCommandOutboxService.enqueueOnlineCommands(List.of(command));
        } catch (RuntimeException ex) {
            releaseAllocationAfterFailure(account.getId(), allocation.proxyId(), ex);
            throw ex;
        }
        markPendingAfterEnqueueForSystemSource(List.of(account.getId()), source);
        log.info("账号上线 outbox 已受理 accountId={} allocatedProxyId={} commandIds={} inserted={}",
                account.getId(), allocation.proxyId(), enqueueResult.commandIds().size(), enqueueResult.inserted());

        // 5. 对外返回本地受理结果;worker 路由信息要等消费端执行后再由状态回写补齐。
        return toOutboxAcceptedVO(account.getId(), protocolAccountId, System.currentTimeMillis());
    }

    /**
     * 批量自动分配代理并投递上线命令。
     *
     * <p>本方法只保证"批量上线命令已写入 outbox",不等待账号真正在线。为了避免请求线程做 N 次协议调用,
     * 账号和凭据批量查询后,统一交给 outbox service 批量落库并在事务提交后发 Kafka。
     * 日志只记录账号数、代理 ID、状态汇总和耗时,不打印凭据或代理密码。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountBatchOnlineVO onlineBatch(List<Long> accountIds) {
        List<Long> ids = normalizeBatchAccountIds(accountIds);
        log.info("账号批量上线开始 requested={}", ids.size());
        updateDesiredLoginStateOrThrow(ids, AccountLoginStateCode.ONLINE, System.currentTimeMillis());

        AccountBatchOnlineVO vo = enqueueOnlineBatch(
                ids,
                SOURCE_BATCH_ONLINE,
                () -> ipProxyService.allocateOnlineEndpoints(allocationRequests(ids)));
        log.info("账号批量上线 outbox 已受理 requested={} submitted={} accepted={} timeout={} proxyRequired={} "
                        + "error={} remote={} elapsedMs={}",
                vo.requested(), vo.submitted(), vo.accepted(), vo.timeout(), vo.proxyRequired(),
                vo.error(), vo.remote(), vo.elapsedMs());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountBatchOnlineVO onlineBatchWithProtocolBackends(List<AccountLifecycleCommandItem> accounts) {
        List<AccountLifecycleCommandItem> items = normalizeLifecycleCommandItems(accounts);
        List<Long> ids = items.stream().map(AccountLifecycleCommandItem::accountId).toList();
        Map<Long, ProtocolBackend> protocolBackendByAccountId = protocolBackendByAccountId(items);
        log.info("账号批量上线开始 requested={} protocolBackendFromRequest=true", ids.size());
        updateDesiredLoginStateOrThrow(ids, AccountLoginStateCode.ONLINE, System.currentTimeMillis());

        AccountBatchOnlineVO vo = enqueueOnlineBatch(
                ids,
                SOURCE_BATCH_ONLINE,
                () -> ipProxyService.allocateOnlineEndpoints(allocationRequests(ids)),
                protocolBackendByAccountId);
        log.info("账号批量上线 outbox 已受理 requested={} submitted={} accepted={} timeout={} proxyRequired={} "
                        + "error={} remote={} elapsedMs={} protocolBackendFromRequest=true",
                vo.requested(), vo.submitted(), vo.accepted(), vo.timeout(), vo.proxyRequired(),
                vo.error(), vo.remote(), vo.elapsedMs());
        return vo;
    }

    /**
     * 对即将删除的代理绑定账号发起在线账号换 IP 重登。
     *
     * <p>本方法只处理当前登录态为 ONLINE 的账号:先由 resource 域返回待删代理当前绑定账号,
     * 再按 account_state.login_state 过滤在线账号。离线账号保持原样,不分配新代理、不写 outbox。
     * 在线账号会复用批量上线 outbox 编排,但分配代理时排除本次待删除的代理 ID,
     * 避免旧代理释放后又被同一批重登选回。</p>
     *
     * @param proxyIds 即将删除的代理 ID 列表
     * @return 在线账号重登命令的 outbox 受理结果;无在线账号时返回零计数结果
     */
    @Override
    public AccountBatchOnlineVO reloginOnlineAccountsByProxyIds(List<Long> proxyIds) {
        List<Long> normalizedProxyIds = normalizeProxyIds(proxyIds);
        if (normalizedProxyIds.isEmpty()) {
            return emptyBatchVO();
        }
        List<Long> boundAccountIds = ipProxyService.findBoundAccountIdsByProxyIds(normalizedProxyIds);
        List<Long> onlineAccountIds = selectOnlineAccountIds(boundAccountIds);
        if (onlineAccountIds.isEmpty()) {
            log.info("IP删除重登跳过:未找到在线绑定账号 proxyCount={} boundAccounts={}",
                    normalizedProxyIds.size(), boundAccountIds.size());
            return emptyBatchVO();
        }
        List<Long> ids = normalizeBatchAccountIds(onlineAccountIds);
        log.info("IP删除重登开始 proxyCount={} onlineAccounts={}", normalizedProxyIds.size(), ids.size());
        AccountBatchOnlineVO vo = enqueueOnlineBatch(
                ids,
                SOURCE_IP_DELETE_RELOGIN,
                () -> ipProxyService.allocateOnlineEndpointsExcludingProxyIds(allocationRequests(ids), normalizedProxyIds));
        log.info("IP删除重登 outbox 已受理 requested={} accepted={}", vo.requested(), vo.accepted());
        return vo;
    }

    /**
     * 批量投递账号下线命令。
     *
     * <p>下线命令只需要账号 ID 和协议账号 ID,不读取凭据、不分配代理、不在请求线程释放代理绑定。
     * 本方法只保证下线命令进入 outbox,最终登录状态和代理释放以后续 Kafka 回写链路为准。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountBatchOnlineVO offlineBatch(List<Long> accountIds) {
        List<Long> ids = normalizeBatchAccountIds(accountIds);
        log.info("账号批量下线开始 requested={}", ids.size());

        return offlineBatch(ids, Map.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountBatchOnlineVO offlineBatchWithProtocolBackends(List<AccountLifecycleCommandItem> accounts) {
        List<AccountLifecycleCommandItem> items = normalizeLifecycleCommandItems(accounts);
        List<Long> ids = items.stream().map(AccountLifecycleCommandItem::accountId).toList();
        log.info("账号批量下线开始 requested={} protocolBackendFromRequest=true", ids.size());
        return offlineBatch(ids, protocolBackendByAccountId(items));
    }

    private AccountBatchOnlineVO offlineBatch(List<Long> ids,
                                              Map<Long, ProtocolBackend> protocolBackendByAccountId) {
        Map<Long, Account> accountsById = loadAccounts(ids);
        updateDesiredLoginStateOrThrow(ids, AccountLoginStateCode.OFFLINE, System.currentTimeMillis());
        int canceledOnlineCommands = protocolCommandOutboxService.cancelPendingAccountOnlineCommands(ids);
        List<PreparedOfflineCommand> prepared = new ArrayList<>(ids.size());
        for (Long accountId : ids) {
            Account account = accountsById.get(accountId);
            String protocolAccountId = requireText(account.getProtocolAccountId(), "协议账号 ID 为空");
            ProtocolBackend protocolBackend = resolveProtocolBackend(account, protocolBackendByAccountId);
            prepared.add(new PreparedOfflineCommand(
                    accountId,
                    protocolAccountId,
                    new ProtocolOfflineCommandRequest(accountId, protocolAccountId, SOURCE_BATCH_OFFLINE,
                            protocolBackend)));
        }

        ProtocolCommandOutboxEnqueueResult enqueueResult = protocolCommandOutboxService.enqueueOfflineCommands(
                prepared.stream().map(PreparedOfflineCommand::command).toList());
        AccountBatchOnlineVO vo = toOutboxOfflineBatchVO(ids.size(), prepared, enqueueResult);
        log.info("账号批量下线 outbox 已受理 requested={} submitted={} accepted={} error={} elapsedMs={} "
                        + "canceledOnlineCommands={} batchId={} commandIds={}",
                vo.requested(), vo.submitted(), vo.accepted(), vo.error(), vo.elapsedMs(),
                canceledOnlineCommands, enqueueResult.batchId(), enqueueResult.commandIds().size());
        return vo;
    }

    /**
     * 加载未软删账号;账号不存在、已软删或入参为空时直接中断上线编排。
     */
    private Account loadAccount(Long accountId) {
        if (accountId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能为空");
        }
        Account account = accountMapper.selectActiveById(accountId);
        if (account == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "账号不存在或已删除: " + accountId);
        }
        return account;
    }

    /**
     * 加载账号凭据;没有凭据时不再解析代理,避免产生无意义的下游调用。
     */
    private AccountCredential loadCredential(Long accountId) {
        AccountCredential credential = credentialMapper.selectByAccountId(accountId);
        if (credential == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号凭据不存在: " + accountId);
        }
        return credential;
    }

    private Map<Long, Account> loadAccounts(List<Long> ids) {
        Map<Long, Account> accountsById = new HashMap<>();
        for (Account account : accountMapper.selectActiveByIds(ids)) {
            accountsById.put(account.getId(), account);
        }
        for (Long id : ids) {
            if (!accountsById.containsKey(id)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "账号不存在或已删除: " + id);
            }
        }
        return accountsById;
    }

    private Map<Long, AccountCredential> loadCredentials(List<Long> ids) {
        Map<Long, AccountCredential> credentialsByAccountId = new HashMap<>();
        for (AccountCredential credential : credentialMapper.selectByAccountIds(ids)) {
            credentialsByAccountId.put(credential.getAccountId(), credential);
        }
        for (Long id : ids) {
            if (!credentialsByAccountId.containsKey(id)) {
                throw new BusinessException(ErrorCode.VALIDATION, "账号凭据不存在: " + id);
            }
        }
        return credentialsByAccountId;
    }

    private void validateTakeoverStates(List<Long> ids) {
        Map<Long, AccountState> statesByAccountId = new HashMap<>();
        for (AccountState state : stateMapper.selectByAccountIds(ids)) {
            statesByAccountId.put(state.getAccountId(), state);
        }
        for (Long id : ids) {
            AccountState state = statesByAccountId.get(id);
            if (state == null
                    || state.getAccountState() == null
                    || state.getAccountState() != AccountStateCode.LOGIN_REPLACED
                    || state.getMuteStatus() != null) {
                throw new BusinessException(ErrorCode.VALIDATION, TAKEOVER_SELECTION_MESSAGE);
            }
        }
    }

    private static boolean isTakeoverEligible(AccountState state) {
        return state != null
                && state.getAccountState() != null
                && state.getAccountState() == AccountStateCode.TAKING_OVER
                && state.getLoginState() != null
                && state.getLoginState() == AccountLoginStateCode.OFFLINE
                && state.getMuteStatus() == null;
    }

    private Optional<AccountOnlineVO> manualOnlineIdempotentResult(Account account, String source) {
        if (!SOURCE_MANUAL_ONLINE.equals(source)) {
            return Optional.empty();
        }
        AccountState state = stateMapper.selectByAccountId(account.getId());
        if (isTakingOver(state)) {
            throw new BusinessException(ErrorCode.VALIDATION, TAKING_OVER_ONLINE_MESSAGE);
        }
        if (state != null && Integer.valueOf(AccountLoginStateCode.PENDING_ONLINE).equals(state.getLoginState())) {
            return Optional.of(skippedOnlineVO(account, ALREADY_PENDING_SOURCE));
        }
        if (state != null && Integer.valueOf(AccountLoginStateCode.ONLINE).equals(state.getLoginState())) {
            return Optional.of(skippedOnlineVO(account, ALREADY_ONLINE_SOURCE));
        }
        return Optional.empty();
    }

    private void validateBatchOnlineStates(List<Long> ids, String source) {
        if (!SOURCE_BATCH_ONLINE.equals(source)) {
            return;
        }
        List<AccountState> states = stateMapper.selectByAccountIds(ids);
        if (states == null) {
            return;
        }
        for (AccountState state : states) {
            if (isTakingOver(state)) {
                throw new BusinessException(ErrorCode.VALIDATION, TAKING_OVER_ONLINE_MESSAGE);
            }
        }
    }

    private static boolean isTakingOver(AccountState state) {
        return state != null
                && state.getAccountState() != null
                && state.getAccountState() == AccountStateCode.TAKING_OVER;
    }

    private void claimManualOnline(List<Long> accountIds, String source) {
        if (!isManualOnlineSource(source)) {
            return;
        }
        int updated = stateMapper.claimPendingOnline(accountIds, System.currentTimeMillis());
        if (updated != accountIds.size()) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "账号登录状态已变化，请刷新后重试: expected=" + accountIds.size() + " updated=" + updated);
        }
    }

    private boolean claimProxyFailedReonline(Long accountId, String source) {
        if (!SOURCE_PROXY_FAILED_REONLINE.equals(source)) {
            return true;
        }
        return stateMapper.claimProxyFailedReonline(accountId, System.currentTimeMillis()) == 1;
    }

    private void updateDesiredLoginStateOrThrow(List<Long> accountIds, int desiredLoginState, long updatedAt) {
        int updated = stateMapper.updateDesiredLoginState(accountIds, desiredLoginState, updatedAt);
        if (updated != accountIds.size()) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "账号期望登录状态更新数量不一致: expected=" + accountIds.size() + " updated=" + updated);
        }
    }

    private IpProxyAllocation allocateOnlineEndpoint(IpProxyAllocationRequest request,
                                                      String source,
                                                      Long failedProxyId) {
        if (!SOURCE_PROXY_FAILED_REONLINE.equals(source) || failedProxyId == null) {
            return ipProxyService.allocateOnlineEndpoint(request);
        }
        IpProxyAccountAllocation allocation = ipProxyService.allocateOnlineEndpointsExcludingProxyIds(
                List.of(request), List.of(failedProxyId)).get(0);
        return new IpProxyAllocation(
                allocation.proxyId(), allocation.endpoint(), allocation.proxySource());
    }

    private static boolean isManualOnlineSource(String source) {
        return SOURCE_MANUAL_ONLINE.equals(source) || SOURCE_BATCH_ONLINE.equals(source);
    }

    private IpProxyAllocationRequest allocationRequest(Long accountId) {
        return allocationRequests(List.of(accountId)).get(0);
    }

    private List<IpProxyAllocationRequest> allocationRequests(List<Long> ids) {
        Map<Long, AccountIpRegionRow> allocationsByAccountId = loadIpAllocationRows(ids);
        Map<Long, String> smartRegionsByAccountId = resolveSmartRegions(ids, allocationsByAccountId);
        List<IpProxyAllocationRequest> requests = new ArrayList<>(ids.size());
        for (Long id : ids) {
            requests.add(toAllocationRequest(id, allocationsByAccountId.get(id), smartRegionsByAccountId.get(id)));
        }
        return requests;
    }

    private Map<Long, AccountIpRegionRow> loadIpAllocationRows(List<Long> ids) {
        Map<Long, AccountIpRegionRow> rowsByAccountId = new HashMap<>();
        for (AccountIpRegionRow row : accountMapper.selectIpRegionsByAccountIds(ids, ImportResult.SUCCESS.getCode())) {
            rowsByAccountId.putIfAbsent(row.getAccountId(), row);
        }
        return rowsByAccountId;
    }

    private Map<Long, String> resolveSmartRegions(List<Long> ids, Map<Long, AccountIpRegionRow> rowsByAccountId) {
        List<AccountIpRegionRow> smartRows = new ArrayList<>();
        List<String> phones = new ArrayList<>();
        for (Long id : ids) {
            AccountIpRegionRow row = rowsByAccountId.get(id);
            if (row != null && IP_ALLOCATION_MODE_SMART.equals(normalizeImportIpAllocationMode(row.getIpAllocationMode()))) {
                smartRows.add(row);
                phones.add(row.getWsPhone());
            }
        }
        if (smartRows.isEmpty()) {
            return Map.of();
        }
        Map<String, String> regionsByPhone = countryService.resolveIpRegionsByPhonePrefix(phones);
        Map<Long, String> regionsByAccountId = new HashMap<>();
        for (AccountIpRegionRow row : smartRows) {
            regionsByAccountId.put(row.getAccountId(), regionsByPhone.get(row.getWsPhone()));
        }
        return regionsByAccountId;
    }

    private IpProxyAllocationRequest toAllocationRequest(Long accountId, AccountIpRegionRow row, String smartRegion) {
        if (row == null) {
            return new IpProxyAllocationRequest(accountId, null, true);
        }
        String mode = normalizeImportIpAllocationMode(row.getIpAllocationMode());
        if (IP_ALLOCATION_MODE_MIXED.equals(mode)) {
            return new IpProxyAllocationRequest(accountId, MIXED_REGION, false);
        }
        if (IP_ALLOCATION_MODE_SMART.equals(mode)) {
            return new IpProxyAllocationRequest(
                    accountId,
                    (smartRegion == null || smartRegion.isBlank()) ? MIXED_REGION : smartRegion,
                    false);
        }
        return new IpProxyAllocationRequest(accountId, row.getIpRegion(), true);
    }

    private static String normalizeImportIpAllocationMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        String trimmed = mode.trim();
        if (IP_ALLOCATION_MODE_MIXED_COUNTRY_LEGACY.equals(trimmed)) {
            return IP_ALLOCATION_MODE_MIXED;
        }
        if (IP_ALLOCATION_MODE_SMART.equals(trimmed) || IP_ALLOCATION_MODE_MIXED.equals(trimmed)) {
            return trimmed;
        }
        return null;
    }

    /**
     * 将导入凭据格式转换为协议层命令使用的凭据格式。
     */
    private static CredentialFormat toCredentialFormat(Integer code) {
        ImportFormat importFormat = ImportFormat.fromCode(code);
        return switch (importFormat) {
            case SIX -> CredentialFormat.SIX_SEGMENT;
            case JSON -> CredentialFormat.BAILEYS_JSON;
            case PARAMS -> CredentialFormat.PARAMS;
        };
    }

    private static AccountOnlineVO toOutboxAcceptedVO(Long accountId, String protocolAccountId, long acceptedAt) {
        return new AccountOnlineVO(
                accountId,
                protocolAccountId,
                true,
                OUTBOX_STATE_SOURCE,
                acceptedAt,
                null,
                null,
                null,
                false);
    }

    private static AccountBatchOnlineVO toOutboxBatchVO(int requested,
                                                        List<PreparedOnlineCommand> prepared,
                                                        ProtocolCommandOutboxEnqueueResult enqueueResult) {
        return new AccountBatchOnlineVO(
                requested,
                prepared.size(),
                enqueueResult.inserted(),
                0,
                0,
                0,
                0,
                0,
                toOutboxItemVOs(prepared),
                List.of());
    }

    private static AccountBatchOnlineVO toOutboxOfflineBatchVO(int requested,
                                                               List<PreparedOfflineCommand> prepared,
                                                               ProtocolCommandOutboxEnqueueResult enqueueResult) {
        return new AccountBatchOnlineVO(
                requested,
                prepared.size(),
                enqueueResult.inserted(),
                0,
                0,
                0,
                0,
                0,
                toOutboxOfflineItemVOs(prepared),
                List.of());
    }

    private AccountBatchOnlineVO enqueueOnlineBatch(List<Long> ids,
                                                    String source,
                                                    OnlineAllocationSupplier allocationSupplier) {
        return enqueueOnlineBatch(ids, source, allocationSupplier, Map.of());
    }

    private AccountBatchOnlineVO enqueueOnlineBatch(List<Long> ids,
                                                    String source,
                                                    OnlineAllocationSupplier allocationSupplier,
                                                    Map<Long, ProtocolBackend> protocolBackendByAccountId) {
        // 先批量加载账号和凭据,在分配代理前完成本地前置校验。
        // 手工上线会在代理分配前用条件 UPDATE 抢占 PENDING 状态，避免重复请求释放并重新分配代理。
        Map<Long, Account> accountsById = loadAccounts(ids);
        validateBatchOnlineStates(ids, source);
        Map<Long, AccountCredential> credentialsByAccountId = loadCredentials(ids);
        claimManualOnline(ids, source);
        List<PreparedOnlineCommand> prepared = new ArrayList<>(ids.size());
        List<IpProxyAccountAllocation> allocations = List.of();
        int snapshotUpdated = 0;

        ProtocolCommandOutboxEnqueueResult enqueueResult;
        try {
            // 代理分配由调用方注入,普通批量上线直接分配空闲代理;删除 IP 前重登会排除待删除代理。
            // allocationSupplier 内部会先释放账号旧绑定,再把新代理置为 IN_USE。
            allocations = allocationSupplier.allocate();
            for (IpProxyAccountAllocation allocation : allocations) {
                Long accountId = allocation.accountId();
                Account account = accountsById.get(accountId);
                AccountCredential credential = credentialsByAccountId.get(accountId);
                CredentialFormat credentialFormat = toCredentialFormat(credential.getCredFormat());
                String protocolAccountId = requireText(account.getProtocolAccountId(), "协议账号 ID 为空");
                String onlineAttemptId = onlineAttemptIdGenerator.nextId();
                ProtocolBackend protocolBackend = resolveProtocolBackend(account, protocolBackendByAccountId);
                // outbox payload 只保存协议执行上线所需的字段:账号、凭据格式、代理 ID、来源和 attemptId。
                // 凭据正文由 dispatcher 发送时再读取,日志也只记录长度,避免敏感信息落日志。
                ProtocolOnlineCommandRequest command = new ProtocolOnlineCommandRequest(
                        accountId,
                        protocolAccountId,
                        credentialFormat,
                        allocation.proxyId(),
                        source,
                        onlineAttemptId,
                        previousAttemptId(accountId, source, null),
                        protocolBackend,
                        isBusinessAccount(account));
                prepared.add(new PreparedOnlineCommand(accountId, protocolAccountId, command));
            }

            snapshotUpdated = updateProxySnapshots(allocations);
            if (snapshotUpdated != allocations.size()) {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "账号代理快照批量更新数量不一致: expected=" + allocations.size()
                                + " updated=" + snapshotUpdated);
            }

            // 批量写入协议命令 outbox。accepted/inserted 只代表本地命令已可靠入队,
            // 不代表 WhatsApp 已经 ONLINE;最终在线状态仍以后续协议层 Kafka 状态事件为准。
            enqueueResult = protocolCommandOutboxService.enqueueOnlineCommands(
                    prepared.stream().map(PreparedOnlineCommand::command).toList());
        } catch (RuntimeException ex) {
            // 如果代理已分配但 outbox 写入失败,按账号+代理精确释放本次分配,避免误释放该账号后续新绑定。
            releaseAllocationsAfterFailure(allocations, ex);
            throw ex;
        }
        // outbox 入队是受理成功边界；待上线只用于列表即时反馈，更新失败不能把已入队命令改判为失败。
        markPendingAfterEnqueueForSystemSource(
                prepared.stream().map(PreparedOnlineCommand::accountId).toList(), source);
        log.info("账号上线 outbox 已受理 source={} requested={} snapshotUpdated={} inserted={} batchId={} commandIds={}",
                source, ids.size(), snapshotUpdated, enqueueResult.inserted(), enqueueResult.batchId(),
                enqueueResult.commandIds().size());
        return toOutboxBatchVO(ids.size(), prepared, enqueueResult);
    }

    private AccountBatchOnlineVO emptyBatchVO() {
        return new AccountBatchOnlineVO(0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
    }

    private static AccountOnlineVO skippedTakeoverVO(Long accountId) {
        return new AccountOnlineVO(
                accountId,
                null,
                false,
                TAKEOVER_SKIPPED_SOURCE,
                System.currentTimeMillis(),
                null,
                null,
                null,
                false);
    }

    private static AccountOnlineVO skippedOnlineVO(Account account, String stateSource) {
        return new AccountOnlineVO(
                account.getId(),
                account.getProtocolAccountId(),
                false,
                stateSource,
                System.currentTimeMillis(),
                null,
                null,
                null,
                false);
    }

    private String previousAttemptId(Long accountId, String source, String failedOnlineAttemptId) {
        if (!SOURCE_PROXY_FAILED_REONLINE.equals(source)) {
            return null;
        }
        if (failedOnlineAttemptId != null && !failedOnlineAttemptId.isBlank()) {
            return failedOnlineAttemptId;
        }
        return accountOnlineAttemptLogService.latestAttemptId(accountId);
    }

    private static List<AccountBatchOnlineItemVO> toOutboxItemVOs(List<PreparedOnlineCommand> prepared) {
        return prepared.stream()
                .map(command -> new AccountBatchOnlineItemVO(
                        command.accountId(),
                        command.protocolAccountId(),
                        BatchOnlineResultStatus.ACCEPTED.name(),
                        null,
                        null))
                .toList();
    }

    private List<Long> selectOnlineAccountIds(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of();
        }
        Set<Long> onlineIds = new LinkedHashSet<>(
                accountMapper.selectOnlineAccountIdsByIds(accountIds, AccountLoginStateCode.ONLINE));
        List<Long> result = new ArrayList<>();
        for (Long accountId : accountIds) {
            if (onlineIds.contains(accountId)) {
                result.add(accountId);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 尽力把已进入 outbox 的账号标记为待上线，供账号列表即时反馈。
     *
     * <p>outbox 成功写入后命令已经被可靠受理；本地状态更新失败不能向调用方返回失败，
     * 否则用户重试会重复写入上线命令。最终登录状态仍由协议层 Kafka 事件回填。</p>
     *
     * @param accountIds 已成功写入上线 outbox 的账号 ID
     */
    private void markPendingOnline(List<Long> accountIds) {
        try {
            long now = System.currentTimeMillis();
            int updated = stateMapper.markPendingOnline(accountIds, now);
            if (updated != accountIds.size()) {
                log.warn("账号上线待回传状态更新数量不一致 expected={} updated={} accountIds={}",
                        accountIds.size(), updated, accountIds);
            }
        } catch (RuntimeException exception) {
            log.error("账号上线待回传状态更新失败 accountCount={} errorType={}",
                    accountIds.size(), exception.getClass().getSimpleName(), exception);
        }
    }

    /**
     * 系统恢复类上线在 outbox 入队后尽力回写 PENDING。
     *
     * <p>手工单个/批量上线已在分配代理前通过条件 UPDATE 抢占状态，
     * 不能再执行无条件回写覆盖并发下线的新状态。</p>
     */
    private void markPendingAfterEnqueueForSystemSource(List<Long> accountIds, String source) {
        if (!isManualOnlineSource(source) && !SOURCE_PROXY_FAILED_REONLINE.equals(source)) {
            markPendingOnline(accountIds);
        }
    }

    private static List<AccountBatchOnlineItemVO> toOutboxOfflineItemVOs(List<PreparedOfflineCommand> prepared) {
        return prepared.stream()
                .map(command -> new AccountBatchOnlineItemVO(
                        command.accountId(),
                        command.protocolAccountId(),
                        BatchOnlineResultStatus.ACCEPTED.name(),
                        null,
                        null))
                .toList();
    }

    /**
     * 校验协议账号 ID 等必填文本字段。
     */
    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, message);
        }
        return value;
    }

    private static List<Long> normalizeBatchAccountIds(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 列表不能为空");
        }
        if (accountIds.size() > BATCH_COMMAND_MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "批量账号命令一次最多 " + BATCH_COMMAND_MAX_SIZE + " 个账号");
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (Long accountId : accountIds) {
            if (accountId == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能为空");
            }
            if (!seen.add(accountId)) {
                throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能重复: " + accountId);
            }
        }
        return List.copyOf(seen);
    }

    private static List<AccountLifecycleCommandItem> normalizeLifecycleCommandItems(
            List<AccountLifecycleCommandItem> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号列表不能为空");
        }
        if (accounts.size() > BATCH_COMMAND_MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "批量账号命令一次最多 " + BATCH_COMMAND_MAX_SIZE + " 个账号");
        }
        Set<Long> seen = new LinkedHashSet<>();
        List<AccountLifecycleCommandItem> normalized = new ArrayList<>(accounts.size());
        for (AccountLifecycleCommandItem account : accounts) {
            if (account == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "账号项不能为空");
            }
            Long accountId = account.accountId();
            if (accountId == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能为空");
            }
            if (account.protocolBackend() == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "协议后端不能为空: " + accountId);
            }
            if (!seen.add(accountId)) {
                throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能重复: " + accountId);
            }
            normalized.add(account);
        }
        return List.copyOf(normalized);
    }

    private static Map<Long, ProtocolBackend> protocolBackendByAccountId(List<AccountLifecycleCommandItem> accounts) {
        Map<Long, ProtocolBackend> protocolBackendByAccountId = new HashMap<>();
        for (AccountLifecycleCommandItem account : accounts) {
            protocolBackendByAccountId.put(account.accountId(), account.protocolBackend());
        }
        return protocolBackendByAccountId;
    }

    private static ProtocolBackend resolveProtocolBackend(Account account,
                                                          Map<Long, ProtocolBackend> protocolBackendByAccountId) {
        ProtocolBackend requestBackend = protocolBackendByAccountId.get(account.getId());
        if (requestBackend != null) {
            return requestBackend;
        }
        return ProtocolBackend.fromProtocolId(account.getProtocolId());
    }

    private static boolean isBusinessAccount(Account account) {
        return account != null && Integer.valueOf(2).equals(account.getAccountType());
    }

    private static List<Long> normalizeProxyIds(List<Long> proxyIds) {
        if (proxyIds == null || proxyIds.isEmpty()) {
            return List.of();
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (Long proxyId : proxyIds) {
            if (proxyId == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "代理 ID 不能为空");
            }
            seen.add(proxyId);
        }
        return List.copyOf(seen);
    }

    /**
     * 只用于日志的凭据长度,避免直接打印凭据 JSON。
     */
    private static int credentialLength(String credentialJson) {
        return credentialJson == null ? 0 : credentialJson.length();
    }

    /**
     * 按固定窗口批量更新账号代理快照，避免批量上线产生每账号一条 UPDATE。
     */
    private int updateProxySnapshots(List<IpProxyAccountAllocation> allocations) {
        if (allocations == null || allocations.isEmpty()) {
            return 0;
        }
        long updatedAt = System.currentTimeMillis();
        int updated = 0;
        for (int from = 0; from < allocations.size(); from += PROXY_SNAPSHOT_BATCH_SIZE) {
            int to = Math.min(from + PROXY_SNAPSHOT_BATCH_SIZE, allocations.size());
            List<AccountState> snapshots = allocations.subList(from, to).stream()
                    .map(allocation -> toProxySnapshot(allocation, updatedAt))
                    .toList();
            updated += stateMapper.updateProxySnapshots(snapshots);
        }
        return updated;
    }

    private static AccountState toProxySnapshot(IpProxyAccountAllocation allocation, long updatedAt) {
        ProxyEndpoint endpoint = allocation.endpoint();
        AccountState row = new AccountState();
        row.setAccountId(allocation.accountId());
        row.setTruthIp(clamp(endpoint == null ? null : endpoint.host(), TRUTH_IP_MAX_LENGTH));
        row.setProxyCountry(clamp(endpoint == null ? null : endpoint.country(), PROXY_COUNTRY_MAX_LENGTH));
        row.setProxySource(clamp(allocation.proxySource(), PROXY_SOURCE_MAX_LENGTH));
        row.setUpdatedAt(updatedAt);
        return row;
    }

    private static String clamp(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private void releaseAllocationsAfterFailure(List<IpProxyAccountAllocation> allocations, RuntimeException original) {
        if (allocations.isEmpty()) {
            return;
        }
        try {
            ipProxyService.releaseOnlineAllocations(allocations);
        } catch (RuntimeException releaseEx) {
            original.addSuppressed(releaseEx);
            log.error("账号批量上线失败后批量释放代理失败 count={}", allocations.size(), releaseEx);
        }
    }

    private void releaseAllocationAfterFailure(Long accountId, Long proxyId, RuntimeException original) {
        try {
            ipProxyService.releaseOnlineAllocation(accountId, proxyId);
        } catch (RuntimeException releaseEx) {
            original.addSuppressed(releaseEx);
            log.error("账号上线失败后释放代理失败 accountId={} allocatedProxyId={}", accountId, proxyId, releaseEx);
        }
    }

    private record PreparedOnlineCommand(
            Long accountId,
            String protocolAccountId,
            ProtocolOnlineCommandRequest command
    ) {
    }

    private record PreparedOfflineCommand(
            Long accountId,
            String protocolAccountId,
            ProtocolOfflineCommandRequest command
    ) {
    }

    @FunctionalInterface
    private interface OnlineAllocationSupplier {
        List<IpProxyAccountAllocation> allocate();
    }
}
