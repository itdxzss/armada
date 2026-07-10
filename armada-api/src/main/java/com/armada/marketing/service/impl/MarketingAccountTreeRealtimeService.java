package com.armada.marketing.service.impl;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.vo.MarketingAccountTreeAccountRow;
import com.armada.marketing.model.vo.MarketingAccountOccupancyOwnerRow;
import com.armada.marketing.model.vo.MarketingAccountTreeVO;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import com.armada.marketing.model.vo.MarketingTreeAccountVO;
import com.armada.marketing.model.vo.MarketingTreeGroupVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 营销任务账号树查库服务。
 *
 * <p>账号和群列表都读取 Armada 本地库。协议层通过 account.groups_reported 异步刷新
 * account_group_membership,本服务不再在用户点击账号时调用协议实时查群。</p>
 */
@Service
public class MarketingAccountTreeRealtimeService {

    private static final Logger log = LoggerFactory.getLogger(MarketingAccountTreeRealtimeService.class);

    private static final int BASELINE_PENDING = AccountGroupBaselineStateCode.PENDING;
    private static final String STATUS_ONLINE = "ONLINE";
    private static final String STATUS_OFFLINE = "OFFLINE";
    private static final String STATUS_RISK = "RISK";
    private static final String STATUS_BANNED = "BANNED";
    private static final String STATUS_MUTED = "MUTED";

    private final MarketingTaskMapper taskMapper;
    private final MarketingAccountOccupancyService occupancyService;

    /**
     * 创建营销账号树查库服务。
     *
     * @param taskMapper       营销任务 mapper,用于查询账号和账号当前群候选
     * @param occupancyService 普通营销账号占用服务
     */
    public MarketingAccountTreeRealtimeService(MarketingTaskMapper taskMapper,
                                               MarketingAccountOccupancyService occupancyService) {
        this.taskMapper = taskMapper;
        this.occupancyService = occupancyService;
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

        Map<Long, MarketingAccountOccupancyOwnerRow> owners = occupancyService.loadActiveOwners(
                accounts.stream().map(MarketingAccountTreeAccountRow::getAccountId).toList());
        List<MarketingTreeAccountVO> nodes = accounts.stream()
                .map(account -> toAccountVO(account, owners.get(account.getAccountId()), false, List.of()))
                .toList();
        log.info("营销账号树首屏查询完成 groupId={} accounts={} occupiedAccounts={}",
                groupId, nodes.size(), owners.size());
        return new MarketingAccountTreeVO(nodes);
    }

    /**
     * 懒加载单个账号当前库内可营销群。
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
        MarketingAccountOccupancyOwnerRow owner = occupancyService
                .loadActiveOwners(List.of(accountId))
                .get(accountId);

        try {
            if (!selectable(account, status(account), owner)) {
                return toAccountVO(account, owner, false, List.of());
            }
            List<MarketingTreeGroupVO> groups = taskMapper.selectDynamicTargetGroups(accountId, null)
                    .stream()
                    .map(MarketingAccountTreeRealtimeService::toGroupVO)
                    .toList();
            log.info("营销账号懒加载查库完成 accountId={} visibleGroups={}",
                    account.getAccountId(), groups.size());
            return toAccountVO(account, owner, false, groups);
        } catch (RuntimeException ex) {
            log.warn("营销账号懒加载查库失败 accountId={}", account.getAccountId(), ex);
            return toAccountVO(account, owner, true, List.of());
        }
    }

    private static MarketingTreeGroupVO toGroupVO(MarketingTargetCandidateRow row) {
        return new MarketingTreeGroupVO(
                row.getGroupLinkId(),
                row.getGroupJid(),
                row.getGroupName(),
                row.getGroupLinkUrl(),
                null);
    }

    private MarketingTreeAccountVO toAccountVO(MarketingAccountTreeAccountRow account,
                                               MarketingAccountOccupancyOwnerRow owner,
                                               boolean groupsError,
                                               List<MarketingTreeGroupVO> groups) {
        String status = status(account);
        boolean selectable = selectable(account, status, owner);
        int groupCount = groups == null || groups.isEmpty()
                ? Math.max(0, account.getGroupCount() == null ? 0 : account.getGroupCount())
                : groups.size();
        if (baselineState(account) == BASELINE_PENDING) {
            groupCount = 0;
        }
        return new MarketingTreeAccountVO(
                account.getAccountId(),
                account.getWsPhone(),
                status,
                statusText(status),
                groupCount,
                selectable,
                disabledReason(account, status, owner),
                groupsError,
                groups == null ? List.of() : groups);
    }

    private static String status(MarketingAccountTreeAccountRow account) {
        if (account.getMuteStatus() != null) {
            return STATUS_MUTED;
        }
        if (account.getRiskStatus() != null && account.getRiskStatus() > 1) {
            return STATUS_RISK;
        }
        if (account.getAccountState() != null && account.getAccountState() == AccountStateCode.BANNED) {
            return STATUS_BANNED;
        }
        if (!Integer.valueOf(AccountLoginStateCode.ONLINE).equals(account.getLoginState())) {
            return STATUS_OFFLINE;
        }
        return STATUS_ONLINE;
    }

    private static String statusText(String status) {
        return switch (status) {
            case STATUS_ONLINE -> "在线";
            case STATUS_RISK -> "风控";
            case STATUS_BANNED -> "封禁";
            case STATUS_MUTED -> "禁言";
            default -> "离线";
        };
    }

    private static boolean selectable(MarketingAccountTreeAccountRow account,
                                      String status,
                                      MarketingAccountOccupancyOwnerRow owner) {
        return owner == null
                && STATUS_ONLINE.equals(status)
                && Integer.valueOf(AccountStateCode.NORMAL).equals(account.getAccountState())
                && baselineState(account) != BASELINE_PENDING
                && account.getProtocolAccountId() != null
                && !account.getProtocolAccountId().isBlank();
    }

    private String disabledReason(MarketingAccountTreeAccountRow account,
                                  String status,
                                  MarketingAccountOccupancyOwnerRow owner) {
        if (owner != null) {
            return MarketingAccountOccupancyService.selectionOccupiedMessage(owner);
        }
        if (selectable(account, status, null)) {
            return null;
        }
        if (baselineState(account) == BASELINE_PENDING) {
            return "群同步中";
        }
        if (account.getProtocolAccountId() == null || account.getProtocolAccountId().isBlank()) {
            return "协议账号缺失";
        }
        if (STATUS_ONLINE.equals(status)
                && !Integer.valueOf(AccountStateCode.NORMAL).equals(account.getAccountState())) {
            return "账号不可用";
        }
        return statusText(status);
    }

    private static int baselineState(MarketingAccountTreeAccountRow account) {
        return account.getGroupBaselineState() == null ? BASELINE_PENDING : account.getGroupBaselineState();
    }
}
