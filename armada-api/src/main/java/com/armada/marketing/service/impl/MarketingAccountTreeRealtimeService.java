package com.armada.marketing.service.impl;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.group.service.AccountGroupMembershipSnapshotService;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.vo.MarketingAccountTreeAccountRow;
import com.armada.marketing.model.vo.MarketingAccountTreeVO;
import com.armada.marketing.model.vo.MarketingTreeAccountVO;
import com.armada.marketing.model.vo.MarketingTreeGroupVO;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import com.armada.platform.protocol.port.AccountParticipatingGroupPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 营销任务账号树懒加载查群服务。
 *
 * <p>账号候选由本地库按在线、风控、禁言等条件筛选;群列表只在用户展开某个账号时调用协议层实时查询,
 * 再用 {@code account_group_baseline.baseline_group_jids} 做差集,避免导入前旧群进入营销候选。</p>
 */
@Service
public class MarketingAccountTreeRealtimeService {

    private static final Logger log = LoggerFactory.getLogger(MarketingAccountTreeRealtimeService.class);

    private static final int BASELINE_PENDING = AccountGroupBaselineStateCode.PENDING;
    private static final int BASELINE_CAPTURED = AccountGroupBaselineStateCode.CAPTURED;
    private static final int BASELINE_DISABLED = AccountGroupBaselineStateCode.DISABLED;
    private static final int PROTOCOL_GROUP_QUERY_CONCURRENCY = 5;
    private static final String ACCOUNT_STATUS_ONLINE = "ONLINE";

    private final MarketingTaskMapper taskMapper;
    private final AccountParticipatingGroupPort groupPort;
    private final AccountGroupMembershipSnapshotService snapshotService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建营销账号树实时查群服务。
     *
     * @param taskMapper          营销任务 mapper,用于查询账号候选
     * @param groupPort           协议层实时查群端口
     * @param snapshotService     账号可见群关系快照写入服务
     * @param objectMapper        JSON 序列化器
     * @param transactionTemplate 单账号快照写入事务模板
     */
    public MarketingAccountTreeRealtimeService(MarketingTaskMapper taskMapper,
                                               AccountParticipatingGroupPort groupPort,
                                               AccountGroupMembershipSnapshotService snapshotService,
                                               ObjectMapper objectMapper,
                                               TransactionTemplate transactionTemplate) {
        this.taskMapper = taskMapper;
        this.groupPort = groupPort;
        this.snapshotService = snapshotService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 查询指定账号分组下新增营销任务可选择的账号树首屏。
     *
     * <p>本方法只读取本地账号候选,不调用协议层查群。群列表由 {@link #accountGroups(Long)}
     * 在用户展开单个账号时懒加载,避免大分组打开抽屉时出现前端超时。</p>
     *
     * @param groupId 账号分组 ID;为空时返回空树
     * @return 只包含账号节点的营销账号树
     */
    public MarketingAccountTreeVO accountTree(Long groupId) {
        if (groupId == null) {
            return new MarketingAccountTreeVO(List.of());
        }
        List<MarketingAccountTreeAccountRow> accounts = taskMapper.selectAccountTreeAccounts(groupId);
        if (accounts.isEmpty()) {
            log.info("营销账号树首屏查询 groupId={} accounts=0", groupId);
            return new MarketingAccountTreeVO(List.of());
        }

        List<MarketingTreeAccountVO> nodes = accounts.stream()
                .map(account -> toAccountVO(account, false, List.of()))
                .toList();
        log.info("营销账号树首屏查询完成 groupId={} accounts={}", groupId, nodes.size());
        return new MarketingAccountTreeVO(nodes);
    }

    /**
     * 懒加载单个账号的实时可营销群。
     *
     * <p>本接口不校验账号分组归属。前端只会对首屏账号树里的账号触发懒加载,
     * 后端保留租户隔离、在线、风控、禁言等账号候选条件,并复用 baseline 排除逻辑。</p>
     *
     * @param accountId 账号 ID
     * @return 账号节点及其可营销群
     */
    public MarketingTreeAccountVO accountGroups(Long accountId) {
        if (accountId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号不能为空");
        }
        MarketingAccountTreeAccountRow account = taskMapper.selectAccountTreeAccount(accountId);
        if (account == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号不可用: " + accountId);
        }

        AccountParticipatingGroupResult result;
        try {
            result = resultsByProtocolAccountId(groupPort.listBatch(
                    List.of(account.getProtocolAccountId()), PROTOCOL_GROUP_QUERY_CONCURRENCY))
                    .get(account.getProtocolAccountId());
        } catch (RuntimeException ex) {
            log.warn("营销账号懒加载协议查群失败 accountId={} protocolAccountId={}",
                    account.getAccountId(), account.getProtocolAccountId(), ex);
            return toAccountVO(account, true, List.of());
        }
        if (result == null || !result.success()) {
            log.warn("营销账号懒加载单账号查群失败 accountId={} protocolAccountId={} error={}",
                    account.getAccountId(), account.getProtocolAccountId(), result == null ? "missing_result" : result.error());
            return toAccountVO(account, true, List.of());
        }

        MarketingTreeAccountVO node = refreshAccount(account, result);
        log.info("营销账号懒加载查群完成 accountId={} protocolAccountId={} groupsError={} visibleGroups={}",
                account.getAccountId(), account.getProtocolAccountId(), node.groupsError(), node.groups().size());
        return node;
    }

    private MarketingTreeAccountVO refreshAccount(MarketingAccountTreeAccountRow account,
                                                  AccountParticipatingGroupResult result) {
        try {
            List<MarketingTreeGroupVO> groups = transactionTemplate.execute(status -> {
                long now = System.currentTimeMillis();
                Map<String, AccountParticipatingGroupResult.Group> currentGroups = normalizeProtocolGroups(result.groups());
                int baselineState = baselineState(account);
                if (baselineState == BASELINE_PENDING) {
                    log.info("营销账号群树跳过待拍账号 baseline 捕获 accountId={} protocolAccountId={} rawGroups={}",
                            account.getAccountId(), account.getProtocolAccountId(), currentGroups.size());
                    return List.<MarketingTreeGroupVO>of();
                }

                Set<String> baseline = baselineState == BASELINE_CAPTURED
                        ? baselineGroupJids(account.getBaselineGroupJidsJson())
                        : Set.of();
                List<AccountGroupsReportedEvent.Group> visibleGroups = currentGroups.entrySet().stream()
                        .filter(entry -> baselineState == BASELINE_DISABLED || !baseline.contains(entry.getKey()))
                        .map(entry -> toReportedGroup(entry.getKey(), entry.getValue()))
                        .toList();
                List<AccountGroupMembershipSnapshot> snapshots = snapshotService.replaceVisibleGroups(
                        account.getAccountId(), visibleGroups, now);
                log.info("营销账号群树账号刷新 accountId={} protocolAccountId={} baselineState={} rawGroups={} "
                                + "baselineGroups={} visibleGroups={}",
                        account.getAccountId(), account.getProtocolAccountId(), baselineState, currentGroups.size(),
                        baseline.size(), snapshots.size());
                return snapshots.stream().map(MarketingAccountTreeRealtimeService::toGroupVO).toList();
            });
            return toAccountVO(account, false, groups == null ? List.of() : groups);
        } catch (RuntimeException ex) {
            log.warn("营销账号群树账号处理失败 accountId={} protocolAccountId={}",
                    account.getAccountId(), account.getProtocolAccountId(), ex);
            return toAccountVO(account, true, List.of());
        }
    }

    private Set<String> baselineGroupJids(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<>() {
            });
            Set<String> normalized = new LinkedHashSet<>();
            for (String value : values) {
                String groupJid = blankToNull(value);
                if (groupJid != null) {
                    normalized.add(groupJid);
                }
            }
            return normalized;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("账号群基线 JSON 解析失败", ex);
        }
    }

    private static Map<String, AccountParticipatingGroupResult> resultsByProtocolAccountId(
            List<AccountParticipatingGroupResult> results) {
        Map<String, AccountParticipatingGroupResult> mapped = new LinkedHashMap<>();
        if (results == null) {
            return mapped;
        }
        for (AccountParticipatingGroupResult result : results) {
            String accountId = blankToNull(result.protocolAccountId());
            if (accountId != null) {
                mapped.putIfAbsent(accountId, result);
            }
        }
        return mapped;
    }

    private static Map<String, AccountParticipatingGroupResult.Group> normalizeProtocolGroups(
            List<AccountParticipatingGroupResult.Group> groups) {
        Map<String, AccountParticipatingGroupResult.Group> mapped = new LinkedHashMap<>();
        if (groups == null) {
            return mapped;
        }
        for (AccountParticipatingGroupResult.Group group : groups) {
            String groupJid = blankToNull(group.groupJid());
            if (groupJid != null) {
                mapped.putIfAbsent(groupJid, group);
            }
        }
        return mapped;
    }

    private static AccountGroupsReportedEvent.Group toReportedGroup(String groupJid,
                                                                    AccountParticipatingGroupResult.Group group) {
        return new AccountGroupsReportedEvent.Group(
                groupJid,
                group.subject(),
                group.memberCount(),
                group.ownerJid(),
                null,
                group.admin(),
                group.announceOnly(),
                null);
    }

    private static MarketingTreeGroupVO toGroupVO(AccountGroupMembershipSnapshot snapshot) {
        return new MarketingTreeGroupVO(
                snapshot.groupLinkId(),
                snapshot.groupJid(),
                snapshot.groupName(),
                snapshot.linkUrl(),
                Boolean.TRUE.equals(snapshot.admin()));
    }

    private static MarketingTreeAccountVO toAccountVO(MarketingAccountTreeAccountRow account,
                                                      boolean groupsError,
                                                      List<MarketingTreeGroupVO> groups) {
        return new MarketingTreeAccountVO(
                account.getAccountId(), account.getWsPhone(), ACCOUNT_STATUS_ONLINE, groupsError, groups);
    }

    private static int baselineState(MarketingAccountTreeAccountRow account) {
        return account.getGroupBaselineState() == null ? BASELINE_PENDING : account.getGroupBaselineState();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
