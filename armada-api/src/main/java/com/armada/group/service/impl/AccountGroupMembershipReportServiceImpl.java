package com.armada.group.service.impl;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.vo.AccountGroupBaselineRow;
import com.armada.group.service.AccountGroupMembershipReportService;
import com.armada.group.service.AccountGroupMembershipSnapshotService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号当前群列表回报落库服务实现。
 *
 * <p>Kafka listener 线程没有 HTTP 租户上下文,本服务按事件中的 {@code tenantId}
 * 临时重建 {@link TenantContext}。已拍账号只写“当前群 - 登录前 baseline”的差集;
 * 待拍账号只在这里兜底已在途的旧同步命令,捕获 baseline JSON 后清空可见 membership。</p>
 */
@Service
public class AccountGroupMembershipReportServiceImpl implements AccountGroupMembershipReportService {

    private static final Logger log = LoggerFactory.getLogger(AccountGroupMembershipReportServiceImpl.class);
    private static final int BASELINE_PENDING = AccountGroupBaselineStateCode.PENDING;
    private static final int BASELINE_CAPTURED = AccountGroupBaselineStateCode.CAPTURED;

    private final AccountGroupMembershipMapper membershipMapper;
    private final AccountGroupMembershipSnapshotService snapshotService;
    private final ObjectMapper objectMapper;

    /**
     * 创建账号当前群列表回报落库服务。
     *
     * @param membershipMapper 账号群关系 mapper
     * @param snapshotService  账号可见群关系快照写入服务
     * @param objectMapper     JSON 解析器
     */
    public AccountGroupMembershipReportServiceImpl(AccountGroupMembershipMapper membershipMapper,
                                                   AccountGroupMembershipSnapshotService snapshotService,
                                                   ObjectMapper objectMapper) {
        this.membershipMapper = membershipMapper;
        this.snapshotService = snapshotService;
        this.objectMapper = objectMapper;
    }

    /**
     * 应用协议层 {@code account.groups_reported} 回报事件。
     *
     * <p>协议层返回的是账号当前全部参与群。待拍账号只捕获本次全量群为 baseline,
     * 已拍账号才过滤 baseline 旧群并写入当前可见群关系。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyGroupsReported(AccountGroupsReportedEvent event) {
        validate(event);
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            long now = System.currentTimeMillis();
            long syncAt = event.reportedAt() == null ? now : event.reportedAt();
            AccountGroupBaselineRow baselineRow = membershipMapper.selectAccountBaselineRow(event.accountId());
            if (baselineRow == null) {
                log.warn("账号群列表事件找不到活跃账号 tenantId={} accountId={} protocolAccountId={} eventId={}",
                        event.tenantId(), event.accountId(), event.protocolAccountId(), event.eventId());
                return;
            }
            if (baselineState(baselineRow) == BASELINE_PENDING) {
                capturePendingBaseline(event, syncAt, now);
                return;
            }
            Set<String> baseline = loadBaselineGroupJids(baselineRow);
            Map<String, AccountGroupsReportedEvent.Group> visibleGroups = visibleGroups(event.groups(), baseline);
            snapshotService.replaceVisibleGroups(event.accountId(), List.copyOf(visibleGroups.values()), syncAt);
            log.info("账号群列表事件已回写 tenantId={} accountId={} protocolAccountId={} rawGroups={} "
                            + "baselineGroups={} visibleGroups={} eventId={}",
                    event.tenantId(), event.accountId(), event.protocolAccountId(), event.groups().size(),
                    baseline.size(), visibleGroups.size(), event.eventId());
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    /**
     * 兜底处理待拍账号的异步群回报。
     *
     * <p>定时同步已不再扫描待拍账号,但历史 outbox 或并发中的旧命令仍可能回报群列表。
     * 这里只允许账号仍处于待拍状态时捕获 baseline JSON,随后清空当前可见 membership,
     * 防止导入前旧群进入营销候选。</p>
     *
     * @param event  协议层账号群列表回报事件
     * @param syncAt 协议查询时间(epoch 毫秒)
     * @param now    本次落库时间(epoch 毫秒)
     */
    private void capturePendingBaseline(AccountGroupsReportedEvent event, long syncAt, long now) {
        List<String> baselineGroupJids = normalizedGroupJids(event.groups());
        String json;
        try {
            json = objectMapper.writeValueAsString(baselineGroupJids);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群基线 JSON 序列化失败");
        }
        int captured = membershipMapper.capturePendingAccountGroupBaseline(
                event.accountId(), json, baselineGroupJids.size(), syncAt, now);
        if (captured <= 0) {
            log.warn("待拍账号群基线捕获被跳过 tenantId={} accountId={} protocolAccountId={} eventId={}",
                    event.tenantId(), event.accountId(), event.protocolAccountId(), event.eventId());
            return;
        }
        int updated = membershipMapper.markAccountBaselineCaptured(event.accountId(), now);
        if (updated <= 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "账号群基线状态更新失败");
        }
        snapshotService.replaceVisibleGroups(event.accountId(), List.of(), syncAt);
        log.info("待拍账号群基线已由异步回报捕获 tenantId={} accountId={} protocolAccountId={} rawGroups={} "
                        + "baselineGroups={} stateUpdated={} eventId={}",
                event.tenantId(), event.accountId(), event.protocolAccountId(), event.groups().size(),
                baselineGroupJids.size(), updated, event.eventId());
    }

    /**
     * 读取已拍账号的 baseline 群 JID 集合。
     *
     * <p>只有 {@code group_baseline_state=2} 才启用差集过滤;待拍账号已在上游分支处理,
     * 不启用 baseline 的账号返回空集合,表示当前群全部可见。</p>
     *
     * @param baselineRow 账号 baseline 状态行
     * @return 归一化并去重后的 baseline 群 JID 集合
     */
    private Set<String> loadBaselineGroupJids(AccountGroupBaselineRow baselineRow) {
        if (baselineState(baselineRow) != BASELINE_CAPTURED) {
            return Set.of();
        }
        String json = baselineRow.getBaselineGroupJidsJson();
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        List<String> groupJids;
        try {
            groupJids = objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群基线 JSON 解析失败");
        }
        Set<String> baseline = new LinkedHashSet<>();
        for (String groupJid : groupJids) {
            String normalized = normalizeJid(groupJid);
            if (normalized != null) {
                baseline.add(normalized);
            }
        }
        return baseline;
    }

    /**
     * 从协议回报中提取用于 baseline JSON 的群 JID 列表。
     *
     * <p>使用 {@link LinkedHashSet} 保留协议返回顺序并去重,写入 JSON 后便于排查“导入时已有群”快照。</p>
     *
     * @param groups 协议层回报的当前参与群
     * @return 去空白、去重后的群 JID 列表
     */
    private static List<String> normalizedGroupJids(List<AccountGroupsReportedEvent.Group> groups) {
        Set<String> deduped = new LinkedHashSet<>();
        for (AccountGroupsReportedEvent.Group group : groups) {
            String groupJid = normalizeJid(group.groupJid());
            if (groupJid != null) {
                deduped.add(groupJid);
            }
        }
        return new ArrayList<>(deduped);
    }

    /**
     * 读取账号 baseline 状态并处理历史空值。
     *
     * <p>历史数据若状态为空,按待拍处理,避免在没有明确 baseline 的情况下把旧群写入可见 membership。</p>
     *
     * @param baselineRow 账号 baseline 状态行
     * @return baseline 状态码
     */
    private static int baselineState(AccountGroupBaselineRow baselineRow) {
        Integer state = baselineRow.getGroupBaselineState();
        return state == null ? BASELINE_PENDING : state;
    }

    /**
     * 计算本次应写入当前 membership 的可见群。
     *
     * <p>协议层返回的是账号当前全部参与群;这里剔除无效 JID 和 baseline 旧群,
     * 相同 JID 重复出现时保留协议返回的第一条。</p>
     *
     * @param groups   协议层回报的当前参与群
     * @param baseline 导入前 baseline 群 JID 集合
     * @return 按群 JID 去重后的当前可见群
     */
    private static Map<String, AccountGroupsReportedEvent.Group> visibleGroups(
            List<AccountGroupsReportedEvent.Group> groups,
            Set<String> baseline) {
        Map<String, AccountGroupsReportedEvent.Group> visible = new LinkedHashMap<>();
        for (AccountGroupsReportedEvent.Group group : groups) {
            String groupJid = normalizeJid(group.groupJid());
            if (groupJid == null || baseline.contains(groupJid)) {
                continue;
            }
            visible.putIfAbsent(groupJid, group);
        }
        return visible;
    }

    /**
     * 校验异步群回报事件的最小必需字段。
     *
     * <p>事件来自 Kafka,缺少租户、账号或群列表时无法安全恢复租户上下文和写入账号维度事实,
     * 因此直接抛业务异常让调用链进入失败处理。</p>
     *
     * @param event 协议层账号群列表回报事件
     */
    private static void validate(AccountGroupsReportedEvent event) {
        if (event == null || event.tenantId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群列表事件缺少 tenantId");
        }
        if (event.accountId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群列表事件缺少 accountId");
        }
        if (event.groups() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群列表事件缺少 groups");
        }
    }

    /**
     * 归一化协议层返回的群 JID。
     *
     * @param value 原始群 JID
     * @return 去除首尾空白后的群 JID;空值返回 null
     */
    private static String normalizeJid(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized;
    }

    /**
     * 将空字符串统一收敛为 null。
     *
     * @param value 原始字符串
     * @return 非空白字符串或 null
     */
    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
