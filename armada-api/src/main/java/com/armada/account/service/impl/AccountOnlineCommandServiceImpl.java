package com.armada.account.service.impl;

import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.entity.ImportFormat;
import com.armada.account.model.vo.AccountBatchOnlineItemVO;
import com.armada.account.model.vo.AccountBatchOnlineRemoteRouteVO;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.model.vo.AccountOnlineVO;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.AccountOnlinePlan;
import com.armada.account.service.AccountOnlineService;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.result.BatchOnlineAccepted;
import com.armada.platform.protocol.model.result.BatchOnlineItemResult;
import com.armada.platform.protocol.model.result.BatchOnlineRemoteRoute;
import com.armada.platform.protocol.model.result.BatchOnlineResultStatus;
import com.armada.platform.protocol.model.result.BatchOnlineSummary;
import com.armada.platform.protocol.model.result.OnlineAccepted;
import com.armada.platform.protocol.model.result.OnlineRouting;
import com.armada.resource.service.IpProxyAccountAllocation;
import com.armada.resource.service.IpProxyAllocation;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账号上线应用服务实现。
 *
 * <p>本类是账号域的上线入口编排:查账号、查自托管凭据、通过 resource 服务自动分配代理,
 * 再复用现有 {@link AccountOnlineService} 投递协议层上线命令。它不修改登录状态,避免把"已受理"误写成"已在线"。</p>
 */
@Service
public class AccountOnlineCommandServiceImpl implements AccountOnlineCommandService {

    private static final Logger log = LoggerFactory.getLogger(AccountOnlineCommandServiceImpl.class);
    private static final int BATCH_ONLINE_MAX_SIZE = 500;
    private static final int BATCH_ONLINE_WAIT_MS = 60_000;

    private final AccountMapper accountMapper;
    private final AccountCredentialMapper credentialMapper;
    private final IpProxyService ipProxyService;
    private final AccountOnlineService accountOnlineService;

    /**
     * 创建账号上线编排服务。
     *
     * <p>这里保持构造器注入,便于单测替换账号、凭据、代理和协议上线服务。</p>
     */
    public AccountOnlineCommandServiceImpl(AccountMapper accountMapper,
                                           AccountCredentialMapper credentialMapper,
                                           IpProxyService ipProxyService,
                                           AccountOnlineService accountOnlineService) {
        this.accountMapper = accountMapper;
        this.credentialMapper = credentialMapper;
        this.ipProxyService = ipProxyService;
        this.accountOnlineService = accountOnlineService;
    }

    /**
     * 自动分配代理并上线一个未软删账号。
     *
     * <p>该方法只完成"上线请求被协议层受理"的同步编排,不会把账号本地状态直接改成 ONLINE。
     * 真正是否在线以后续状态刷新为准。日志只记录运营定位需要的 ID、格式、长度和受理结果,
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
            // 3. 协议层只需要凭据格式枚举和原始 JSON;日志只打 JSON 长度,避免凭据泄露。
            CredentialFormat credentialFormat = toCredentialFormat(credential.getCredFormat());
            String protocolAccountId = requireText(account.getProtocolAccountId(), "协议账号 ID 为空");
            log.info("账号上线调用协议层 accountId={} allocatedProxyId={} credentialFormat={} credentialLength={}",
                    account.getId(), allocation.proxyId(), credentialFormat, credentialLength(credential.getCredsJson()));

            AccountOnlinePlan plan = new AccountOnlinePlan(
                    protocolAccountId,
                    credentialFormat,
                    credential.getCredsJson(),
                    allocation.endpoint());

            // 4. accepted 表示协议层已受理上线请求,不等价于 WhatsApp 已经在线;最终状态等 Kafka 异步回填。
            OnlineAccepted accepted = accountOnlineService.online(plan);
            if (!accepted.accepted()) {
                ipProxyService.releaseOnlineAllocation(account.getId(), allocation.proxyId());
            }
            OnlineRouting routing = accepted.routing();
            log.info("账号上线协议层返回 accountId={} allocatedProxyId={} accepted={} stateSource={} ownerWorkerId={} local={}",
                    account.getId(), allocation.proxyId(), accepted.accepted(), accepted.stateSource(),
                    routing.ownerWorkerId(), routing.local());

            // 5. 对外返回受理结果和路由信息,状态落库仍由后续同步流程负责。
            return toVO(account.getId(), accepted);
        } catch (RuntimeException ex) {
            releaseAllocationAfterFailure(account.getId(), allocation.proxyId(), ex);
            throw ex;
        }
    }

    /**
     * 批量自动分配代理并投递上线命令。
     *
     * <p>本方法只保证"批量上线命令已提交给协议层",不等待账号真正在线。为了避免 N 次协议 HTTP,
     * 账号和凭据批量查询后,统一交给 {@link AccountOnlineService#onlineBatch(List, int)} 发一次协议 batch。
     * 日志只记录账号数、代理 ID、状态汇总和耗时,不打印凭据或代理密码。</p>
     */
    @Override
    public AccountBatchOnlineVO onlineBatch(List<Long> accountIds) {
        List<Long> ids = normalizeBatchAccountIds(accountIds);
        log.info("账号批量上线开始 requested={}", ids.size());

        Map<Long, Account> accountsById = loadAccounts(ids);
        Map<Long, AccountCredential> credentialsByAccountId = loadCredentials(ids);
        List<PreparedOnlinePlan> prepared = new ArrayList<>(ids.size());
        List<IpProxyAccountAllocation> allocations = List.of();

        try {
            // 代理分配必须批量做:500 个账号不能退化成 500 个短事务和 1500 次 SQL 往返。
            // resource 域内部会在一个本地短事务里释放旧绑定、锁定空闲代理并批量绑定。
            allocations = ipProxyService.allocateOnlineEndpoints(ids);
            for (IpProxyAccountAllocation allocation : allocations) {
                Long accountId = allocation.accountId();
                Account account = accountsById.get(accountId);
                AccountCredential credential = credentialsByAccountId.get(accountId);
                CredentialFormat credentialFormat = toCredentialFormat(credential.getCredFormat());
                String protocolAccountId = requireText(account.getProtocolAccountId(), "协议账号 ID 为空");
                AccountOnlinePlan plan = new AccountOnlinePlan(
                        protocolAccountId,
                        credentialFormat,
                        credential.getCredsJson(),
                        allocation.endpoint());
                prepared.add(new PreparedOnlinePlan(accountId, protocolAccountId, allocation, plan));
                log.info("账号批量上线已分配代理 accountId={} allocatedProxyId={} credentialFormat={} credentialLength={}",
                        accountId, allocation.proxyId(), credentialFormat, credentialLength(credential.getCredsJson()));
            }

            BatchOnlineAccepted accepted = accountOnlineService.onlineBatch(
                    prepared.stream().map(PreparedOnlinePlan::plan).toList(),
                    BATCH_ONLINE_WAIT_MS);
            AccountBatchOnlineVO vo = toBatchVO(ids.size(), prepared, accepted);
            releaseRejectedAllocations(prepared, accepted);
            log.info("账号批量上线协议层返回 requested={} submitted={} accepted={} timeout={} proxyRequired={} error={} remote={} elapsedMs={}",
                    vo.requested(), vo.submitted(), vo.accepted(), vo.timeout(), vo.proxyRequired(),
                    vo.error(), vo.remote(), vo.elapsedMs());
            return vo;
        } catch (RuntimeException ex) {
            releaseAllocationsAfterFailure(allocations, ex);
            throw ex;
        }
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

    /**
     * 将协议层受理结果映射为接口返回对象。
     */
    private static AccountOnlineVO toVO(Long accountId, OnlineAccepted accepted) {
        OnlineRouting routing = accepted.routing();
        return new AccountOnlineVO(
                accountId,
                accepted.protocolAccountId(),
                accepted.accepted(),
                accepted.stateSource().name(),
                accepted.syncedAt() == null ? null : accepted.syncedAt().toEpochMilli(),
                routing.ownerWorkerId(),
                routing.ownerEndpoint(),
                routing.currentWorkerId(),
                routing.local());
    }

    private static AccountBatchOnlineVO toBatchVO(int requested,
                                                  List<PreparedOnlinePlan> prepared,
                                                  BatchOnlineAccepted accepted) {
        if (accepted == null || accepted.summary() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议层批量上线响应为空");
        }
        Map<String, Long> accountIdByProtocolId = accountIdByProtocolId(prepared);
        BatchOnlineSummary summary = accepted.summary();
        return new AccountBatchOnlineVO(
                requested,
                prepared.size(),
                summary.accepted(),
                summary.timeout(),
                summary.proxyRequired(),
                summary.error(),
                summary.remote(),
                accepted.elapsedMs(),
                toItemVOs(accepted.results(), accountIdByProtocolId),
                toRemoteVOs(accepted.remote(), accountIdByProtocolId));
    }

    private static List<AccountBatchOnlineItemVO> toItemVOs(List<BatchOnlineItemResult> results,
                                                            Map<String, Long> accountIdByProtocolId) {
        if (results == null) {
            return List.of();
        }
        return results.stream()
                .map(result -> new AccountBatchOnlineItemVO(
                        accountIdByProtocolId.get(result.protocolAccountId()),
                        result.protocolAccountId(),
                        result.result() == null ? BatchOnlineResultStatus.ERROR.name() : result.result().name(),
                        result.retryAfterMs(),
                        result.error()))
                .toList();
    }

    private static List<AccountBatchOnlineRemoteRouteVO> toRemoteVOs(List<BatchOnlineRemoteRoute> remote,
                                                                     Map<String, Long> accountIdByProtocolId) {
        if (remote == null) {
            return List.of();
        }
        return remote.stream()
                .map(route -> new AccountBatchOnlineRemoteRouteVO(
                        accountIdByProtocolId.get(route.protocolAccountId()),
                        route.protocolAccountId(),
                        route.ownerWorkerId(),
                        route.ownerEndpoint(),
                        route.note()))
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
        if (accountIds.size() > BATCH_ONLINE_MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION, "批量上线一次最多 " + BATCH_ONLINE_MAX_SIZE + " 个账号");
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

    /**
     * 只用于日志的凭据长度,避免直接打印凭据 JSON。
     */
    private static int credentialLength(String credentialJson) {
        return credentialJson == null ? 0 : credentialJson.length();
    }

    private static Map<String, Long> accountIdByProtocolId(List<PreparedOnlinePlan> prepared) {
        Map<String, Long> mapping = new HashMap<>();
        for (PreparedOnlinePlan plan : prepared) {
            mapping.put(plan.protocolAccountId(), plan.accountId());
        }
        return mapping;
    }

    private void releaseRejectedAllocations(List<PreparedOnlinePlan> prepared, BatchOnlineAccepted accepted) {
        Set<String> acceptedProtocolIds = new HashSet<>();
        if (accepted != null && accepted.results() != null) {
            for (BatchOnlineItemResult result : accepted.results()) {
                if (result.result() == BatchOnlineResultStatus.ACCEPTED) {
                    acceptedProtocolIds.add(result.protocolAccountId());
                }
            }
        }
        List<IpProxyAccountAllocation> toRelease = new ArrayList<>();
        for (PreparedOnlinePlan plan : prepared) {
            if (!acceptedProtocolIds.contains(plan.protocolAccountId())) {
                toRelease.add(plan.allocation());
            }
        }
        releaseAllocationsQuietly(toRelease);
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

    private void releaseAllocationsQuietly(List<IpProxyAccountAllocation> allocations) {
        if (allocations.isEmpty()) {
            return;
        }
        try {
            ipProxyService.releaseOnlineAllocations(allocations);
        } catch (RuntimeException ex) {
            log.error("账号批量上线非受理项批量释放代理失败 count={}", allocations.size(), ex);
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

    private record PreparedOnlinePlan(
            Long accountId,
            String protocolAccountId,
            IpProxyAccountAllocation allocation,
            AccountOnlinePlan plan
    ) {
    }
}
