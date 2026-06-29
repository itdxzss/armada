package com.armada.group.service.impl;

import com.armada.group.mapper.GroupLinkHealthMapper;
import com.armada.group.model.dto.GroupLinkHealthReportedEvent;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.enums.GroupLinkHealthStatus;
import com.armada.group.service.GroupLinkHealthReportService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 群链接健康检测回报落库服务实现。
 *
 * <p>Kafka listener 线程没有 HTTP 租户上下文,本服务按事件中的 {@code tenantId}
 * 临时重建 {@link TenantContext},保证 MyBatis 租户拦截器仍然接管读写隔离。</p>
 */
@Service
public class GroupLinkHealthReportServiceImpl implements GroupLinkHealthReportService {

    private static final Logger log = LoggerFactory.getLogger(GroupLinkHealthReportServiceImpl.class);

    private static final int LAST_HEALTH_ERROR_MAX_LENGTH = 255;

    private static final String HEALTHY = "HEALTHY";
    private static final String AVAILABLE = "AVAILABLE";
    private static final String OK = "OK";
    private static final String BANNED = "BANNED";
    private static final String LINK_INVALID = "LINK_INVALID";
    private static final String INVALID_LINK = "INVALID_LINK";
    private static final String INVITE_REVOKED = "INVITE_REVOKED";
    private static final String GROUP_LINK_REVOKED = "GROUP_LINK_REVOKED";
    private static final String REVOKED = "REVOKED";

    private final GroupLinkHealthMapper healthMapper;

    /**
     * 创建群链接健康检测回报落库服务。
     *
     * @param healthMapper 群链接健康状态 mapper
     */
    public GroupLinkHealthReportServiceImpl(GroupLinkHealthMapper healthMapper) {
        this.healthMapper = healthMapper;
    }

    /**
     * 应用协议层 {@code group.health_reported} 回报事件。
     *
     * <p>事件失败只影响对应链接健康状态,不写 group_link 主表。成员数为空时保留现有
     * {@code current_count},避免失败事件把上一次有效成员数清掉。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyHealthReported(GroupLinkHealthReportedEvent event) {
        validate(event);
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            GroupLinkHealth current = healthMapper.selectByGroupLinkId(event.groupLinkId());
            GroupLinkHealth row = buildHealthRow(event, current);
            healthMapper.upsert(row);
            log.info("群链接健康事件已回写 tenantId={} groupLinkId={} groupJid={} health={} status={} "
                            + "banned={} failureCount={} eventId={} protocolAccountId={}",
                    event.tenantId(), event.groupLinkId(), event.groupJid(), event.health(),
                    row.getHealthStatus(), row.getBanned(), row.getHealthFailureCount(),
                    event.eventId(), event.protocolAccountId());
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    /**
     * 将协议层回报事件转换成可 upsert 的健康状态行。
     *
     * <p>成员数为空时沿用旧值,避免一次失败检测覆盖掉上一次成功检测拿到的有效群人数;
     * 成功检测会清空错误原因并把连续失败次数归零。</p>
     */
    private static GroupLinkHealth buildHealthRow(GroupLinkHealthReportedEvent event, GroupLinkHealth current) {
        long now = System.currentTimeMillis();
        boolean healthy = isHealthy(event.health());
        GroupLinkHealth row = new GroupLinkHealth();
        row.setGroupLinkId(event.groupLinkId());
        row.setHealthStatus(mapStatus(event.health(), event.errorCode()).code());
        row.setBanned(isBanned(event.health(), event.errorCode()));
        row.setCurrentCount(event.memberCount() == null ? currentCount(current) : event.memberCount());
        row.setLastCheckAt(event.checkedAt() == null ? now : event.checkedAt());
        row.setLastHealthError(healthy ? null : failureReason(event.health(), event.errorCode()));
        row.setHealthFailureCount(healthy ? 0 : currentFailureCount(current) + 1);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /**
     * 映射协议层健康语义到 Armada 存储枚举。
     *
     * <p>健康结果直接标记可用;链接撤销/失效类错误单独标记 {@code LINK_INVALID};
     * 其它异常统一归为不可用,由后续巡检继续重试。</p>
     */
    private static GroupLinkHealthStatus mapStatus(String health, String errorCode) {
        if (isHealthy(health)) {
            return GroupLinkHealthStatus.AVAILABLE;
        }
        if (isLinkInvalid(health) || isLinkInvalid(errorCode)) {
            return GroupLinkHealthStatus.LINK_INVALID;
        }
        return GroupLinkHealthStatus.UNAVAILABLE;
    }

    /**
     * 判断协议层回报是否代表检测成功。
     *
     * <p>兼容 HEALTHY/AVAILABLE/OK 三种上游表述,避免协议层命名微调导致状态误判。</p>
     */
    private static boolean isHealthy(String health) {
        String normalized = normalize(health);
        return HEALTHY.equals(normalized) || AVAILABLE.equals(normalized) || OK.equals(normalized);
    }

    /**
     * 判断回报是否明确表示群或检测账号被 WhatsApp 封禁。
     *
     * <p>health 与 errorCode 任一字段带 BANNED 都按封禁处理。</p>
     */
    private static boolean isBanned(String health, String errorCode) {
        return BANNED.equals(normalize(health)) || BANNED.equals(normalize(errorCode));
    }

    /**
     * 判断错误码是否属于“邀请链接本身失效”。
     *
     * <p>这类错误与临时网络/权限失败不同,会落为 {@code LINK_INVALID},
     * 便于业务侧把链接从可用池中剔除。</p>
     */
    private static boolean isLinkInvalid(String value) {
        String normalized = normalize(value);
        return LINK_INVALID.equals(normalized)
                || INVALID_LINK.equals(normalized)
                || INVITE_REVOKED.equals(normalized)
                || GROUP_LINK_REVOKED.equals(normalized)
                || REVOKED.equals(normalized);
    }

    /**
     * 读取已有成员数。
     *
     * <p>失败事件通常拿不到成员数,保留旧值比写 null 更利于列表展示和后续排查。</p>
     */
    private static Integer currentCount(GroupLinkHealth current) {
        return current == null ? null : current.getCurrentCount();
    }

    /**
     * 读取已有连续失败次数。
     *
     * <p>历史为空按 0 处理,确保第一次失败会写成 1。</p>
     */
    private static int currentFailureCount(GroupLinkHealth current) {
        return current == null || current.getHealthFailureCount() == null ? 0 : current.getHealthFailureCount();
    }

    /**
     * 选择落库的失败原因。
     *
     * <p>优先使用更具体的 errorCode;缺失时用 health 兜底,并按列宽截断。</p>
     */
    private static String failureReason(String health, String errorCode) {
        String reason = errorCode == null || errorCode.isBlank() ? health : errorCode;
        return clamp(reason, LAST_HEALTH_ERROR_MAX_LENGTH);
    }

    /**
     * 校验最小落库字段。
     *
     * <p>tenantId 用于重建租户上下文,groupLinkId 用于定位健康行,health 用于状态映射,
     * 三者缺任一项都不能可靠回写。</p>
     */
    private static void validate(GroupLinkHealthReportedEvent event) {
        if (event == null || event.tenantId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群链接健康事件缺少 tenantId");
        }
        if (event.groupLinkId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群链接健康事件缺少 groupLinkId");
        }
        if (event.health() == null || event.health().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "群链接健康事件缺少 health");
        }
    }

    /**
     * 归一化协议层枚举文本。
     *
     * <p>统一 trim 和大写,让映射逻辑对大小写和前后空白不敏感。</p>
     */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().toUpperCase();
    }

    /**
     * 按数据库列宽裁剪字符串。
     *
     * <p>上游错误消息可能较长,落库前截断可避免单条异常事件导致事务失败。</p>
     */
    private static String clamp(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
