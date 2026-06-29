package com.armada.account.service.impl;

import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.ImportFormat;
import com.armada.account.model.vo.AccountBatchOnlineItemVO;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.model.vo.AccountOnlineVO;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.ProtocolOfflineCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolOnlineCommandRequest;
import com.armada.platform.protocol.model.result.BatchOnlineResultStatus;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.resource.service.IpProxyAccountAllocation;
import com.armada.resource.service.IpProxyAllocation;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账号生命周期命令应用服务实现。
 *
 * <p>本类是账号域的上线/下线入口编排:上线负责查凭据、分配代理并写 outbox;
 * 下线只校验账号并写 outbox。它不直接调用协议层,也不修改登录状态,
 * 避免把"已受理"误写成"已在线/已离线"。</p>
 */
@Service
public class AccountOnlineCommandServiceImpl implements AccountOnlineCommandService {

    private static final Logger log = LoggerFactory.getLogger(AccountOnlineCommandServiceImpl.class);
    private static final int BATCH_COMMAND_MAX_SIZE = 500;
    private static final String OUTBOX_STATE_SOURCE = "OUTBOX";
    private static final String SOURCE_MANUAL_ONLINE = "manual_online";
    private static final String SOURCE_BATCH_ONLINE = "batch_online";
    private static final String SOURCE_BATCH_OFFLINE = "batch_offline";
    private static final String SOURCE_IP_DELETE_RELOGIN = "ip_delete_relogin";

    private final AccountMapper accountMapper;
    private final AccountCredentialMapper credentialMapper;
    private final IpProxyService ipProxyService;
    private final ProtocolCommandOutboxService protocolCommandOutboxService;

    /**
     * 创建账号上线编排服务。
     *
     * <p>这里保持构造器注入,便于单测替换账号、凭据、代理和 outbox 服务。</p>
     */
    public AccountOnlineCommandServiceImpl(AccountMapper accountMapper,
                                           AccountCredentialMapper credentialMapper,
                                           IpProxyService ipProxyService,
                                           ProtocolCommandOutboxService protocolCommandOutboxService) {
        this.accountMapper = accountMapper;
        this.credentialMapper = credentialMapper;
        this.ipProxyService = ipProxyService;
        this.protocolCommandOutboxService = protocolCommandOutboxService;
    }

    /**
     * 自动分配代理并上线一个未软删账号。
     *
     * <p>该方法只完成"上线命令已可靠进入 outbox"的同步编排,不会把账号本地状态直接改成 ONLINE。
     * 真正是否在线以后续 Kafka 状态刷新为准。日志只记录运营定位需要的 ID、格式、长度和受理结果,
     * 不输出凭据 JSON、代理账号密码等敏感内容。</p>
     */
    @Override
    public AccountOnlineVO online(Long accountId) {
        log.info("账号上线开始 accountId={}", accountId);

        // 1. 只允许未软删账号继续上线,并读取它对应的自托管凭据。
        Account account = loadAccount(accountId);
        AccountCredential credential = loadCredential(account.getId());

        // 2. 用户点击上线时不复用旧绑定:resource 服务先释放该账号旧 IP,再锁定一条空闲代理并置为使用中。
        IpProxyAllocation allocation = ipProxyService.allocateOnlineEndpoint(account.getId());

        try {
            // 3. outbox 只保存凭据格式和代理 ID;日志只打 JSON 长度,避免凭据泄露。
            CredentialFormat credentialFormat = toCredentialFormat(credential.getCredFormat());
            String protocolAccountId = requireText(account.getProtocolAccountId(), "协议账号 ID 为空");
            log.info("账号上线写入 outbox 前准备 command accountId={} allocatedProxyId={} credentialFormat={} credentialLength={}",
                    account.getId(), allocation.proxyId(), credentialFormat, credentialLength(credential.getCredsJson()));

            ProtocolOnlineCommandRequest command = new ProtocolOnlineCommandRequest(
                    account.getId(),
                    protocolAccountId,
                    credentialFormat,
                    allocation.proxyId(),
                    SOURCE_MANUAL_ONLINE);

            // 4. accepted 表示命令已进入本地 outbox,不等价于 WhatsApp 已经在线;最终状态等 Kafka 异步回填。
            ProtocolCommandOutboxEnqueueResult enqueueResult =
                    protocolCommandOutboxService.enqueueOnlineCommands(List.of(command));
            log.info("账号上线 outbox 已受理 accountId={} allocatedProxyId={} commandIds={} inserted={}",
                    account.getId(), allocation.proxyId(), enqueueResult.commandIds().size(), enqueueResult.inserted());

            // 5. 对外返回本地受理结果;worker 路由信息要等消费端执行后再由状态回写补齐。
            return toOutboxAcceptedVO(account.getId(), protocolAccountId, System.currentTimeMillis());
        } catch (RuntimeException ex) {
            releaseAllocationAfterFailure(account.getId(), allocation.proxyId(), ex);
            throw ex;
        }
    }

    /**
     * 批量自动分配代理并投递上线命令。
     *
     * <p>本方法只保证"批量上线命令已写入 outbox",不等待账号真正在线。为了避免请求线程做 N 次协议调用,
     * 账号和凭据批量查询后,统一交给 outbox service 批量落库并在事务提交后发 Kafka。
     * 日志只记录账号数、代理 ID、状态汇总和耗时,不打印凭据或代理密码。</p>
     */
    @Override
    public AccountBatchOnlineVO onlineBatch(List<Long> accountIds) {
        List<Long> ids = normalizeBatchAccountIds(accountIds);
        log.info("账号批量上线开始 requested={}", ids.size());

        AccountBatchOnlineVO vo = enqueueOnlineBatch(
                ids,
                SOURCE_BATCH_ONLINE,
                () -> ipProxyService.allocateOnlineEndpoints(ids));
        log.info("账号批量上线 outbox 已受理 requested={} submitted={} accepted={} timeout={} proxyRequired={} "
                        + "error={} remote={} elapsedMs={}",
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
                () -> ipProxyService.allocateOnlineEndpointsExcludingProxyIds(ids, normalizedProxyIds));
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
    public AccountBatchOnlineVO offlineBatch(List<Long> accountIds) {
        List<Long> ids = normalizeBatchAccountIds(accountIds);
        log.info("账号批量下线开始 requested={}", ids.size());

        Map<Long, Account> accountsById = loadAccounts(ids);
        List<PreparedOfflineCommand> prepared = new ArrayList<>(ids.size());
        for (Long accountId : ids) {
            Account account = accountsById.get(accountId);
            String protocolAccountId = requireText(account.getProtocolAccountId(), "协议账号 ID 为空");
            prepared.add(new PreparedOfflineCommand(
                    accountId,
                    protocolAccountId,
                    new ProtocolOfflineCommandRequest(accountId, protocolAccountId, SOURCE_BATCH_OFFLINE)));
            log.info("账号批量下线写入 outbox 前准备 command accountId={} protocolAccountId={}",
                    accountId, protocolAccountId);
        }

        ProtocolCommandOutboxEnqueueResult enqueueResult = protocolCommandOutboxService.enqueueOfflineCommands(
                prepared.stream().map(PreparedOfflineCommand::command).toList());
        AccountBatchOnlineVO vo = toOutboxOfflineBatchVO(ids.size(), prepared, enqueueResult);
        log.info("账号批量下线 outbox 已受理 requested={} submitted={} accepted={} error={} elapsedMs={} "
                        + "batchId={} commandIds={}",
                vo.requested(), vo.submitted(), vo.accepted(), vo.error(), vo.elapsedMs(),
                enqueueResult.batchId(), enqueueResult.commandIds().size());
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
        Map<Long, Account> accountsById = loadAccounts(ids);
        Map<Long, AccountCredential> credentialsByAccountId = loadCredentials(ids);
        List<PreparedOnlineCommand> prepared = new ArrayList<>(ids.size());
        List<IpProxyAccountAllocation> allocations = List.of();

        try {
            allocations = allocationSupplier.allocate();
            for (IpProxyAccountAllocation allocation : allocations) {
                Long accountId = allocation.accountId();
                Account account = accountsById.get(accountId);
                AccountCredential credential = credentialsByAccountId.get(accountId);
                CredentialFormat credentialFormat = toCredentialFormat(credential.getCredFormat());
                String protocolAccountId = requireText(account.getProtocolAccountId(), "协议账号 ID 为空");
                ProtocolOnlineCommandRequest command = new ProtocolOnlineCommandRequest(
                        accountId,
                        protocolAccountId,
                        credentialFormat,
                        allocation.proxyId(),
                        source);
                prepared.add(new PreparedOnlineCommand(accountId, protocolAccountId, command));
                log.info("账号批量上线写入 outbox 前准备 command accountId={} allocatedProxyId={} source={} "
                                + "credentialFormat={} credentialLength={}",
                        accountId, allocation.proxyId(), source, credentialFormat, credentialLength(credential.getCredsJson()));
            }

            ProtocolCommandOutboxEnqueueResult enqueueResult = protocolCommandOutboxService.enqueueOnlineCommands(
                    prepared.stream().map(PreparedOnlineCommand::command).toList());
            log.info("账号上线 outbox 已受理 source={} requested={} inserted={} batchId={} commandIds={}",
                    source, ids.size(), enqueueResult.inserted(), enqueueResult.batchId(),
                    enqueueResult.commandIds().size());
            return toOutboxBatchVO(ids.size(), prepared, enqueueResult);
        } catch (RuntimeException ex) {
            releaseAllocationsAfterFailure(allocations, ex);
            throw ex;
        }
    }

    private AccountBatchOnlineVO emptyBatchVO() {
        return new AccountBatchOnlineVO(0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
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
