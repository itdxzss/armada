package com.armada.group.service;

import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 记录系统已明确发起并成功确认的 WhatsApp 主动离群事实。 */
@Service
public class WhatsappGroupBusinessDepartureService {

    private static final String SOURCE_TYPE = "BUSINESS_COMMAND";

    private final WhatsappGroupDepartedMemberService departedMemberService;
    private final WhatsappGroupMemberCacheService memberCacheService;

    public WhatsappGroupBusinessDepartureService(
            WhatsappGroupDepartedMemberService departedMemberService,
            WhatsappGroupMemberCacheService memberCacheService) {
        this.departedMemberService = departedMemberService;
        this.memberCacheService = memberCacheService;
    }

    /**
     * 在退群协议命令确认成功后落一条 LEFT 事实。
     *
     * <p>业务命令本身已经明确表达当前账号主动退出，因此不依赖后续可能只有 remove
     * 最终状态、无法区分操作者的协议通知来推断退出方式。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordConfirmedLeave(
            Long tenantId,
            String groupJid,
            String accountPhone,
            long exitedAt,
            String operationId) {
        String normalizedGroupJid = normalizedGroupJid(groupJid);
        String phone = normalizedPhone(accountPhone);
        String normalizedOperationId = required(operationId, "主动退群操作 ID 不能为空");
        if (tenantId == null || exitedAt <= 0 || normalizedGroupJid == null || phone == null) {
            throw new IllegalArgumentException("主动退群事实缺少租户、群、账号或时间");
        }
        WhatsappGroupDepartureFact fact = new WhatsappGroupDepartureFact(
                tenantId,
                normalizedGroupJid,
                phone + "@s.whatsapp.net",
                phone,
                exitedAt,
                "LEFT",
                exitedAt,
                "business-leave:" + normalizedOperationId,
                SOURCE_TYPE);
        List<WhatsappGroupDepartureFact> facts = List.of(fact);
        departedMemberService.saveLatest(facts);
        memberCacheService.applyDepartures(facts);
    }

    /**
     * 在踢人协议命令经同账号 metadata 回读确认后，批量落 REMOVED 事实。
     *
     * <p>该入口同时驱动离群事实表、营销成员缓存和新群模型普通成员 presence，确保
     * {@code 1.0.3-group} 双写期间不会出现旧详情已删除而新成员模型仍显示在群的分叉。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordConfirmedRemovals(
            Long tenantId,
            String groupJid,
            Map<String, String> participantPhones,
            long exitedAt,
            String operationId) {
        String normalizedGroupJid = normalizedGroupJid(groupJid);
        String normalizedOperationId = required(operationId, "踢出成员操作 ID 不能为空");
        if (tenantId == null || exitedAt <= 0 || normalizedGroupJid == null
                || participantPhones == null || participantPhones.isEmpty()) {
            throw new IllegalArgumentException("踢出成员事实缺少租户、群、成员或时间");
        }
        List<WhatsappGroupDepartureFact> facts = new ArrayList<>(participantPhones.size());
        participantPhones.forEach((participantJid, phone) -> facts.add(
                new WhatsappGroupDepartureFact(
                        tenantId,
                        normalizedGroupJid,
                        normalizedParticipantJid(participantJid),
                        normalizedPhone(phone),
                        exitedAt,
                        "REMOVED",
                        exitedAt,
                        "business-remove:" + normalizedOperationId,
                        SOURCE_TYPE)));
        departedMemberService.saveLatest(List.copyOf(facts));
        memberCacheService.applyDepartures(List.copyOf(facts));
    }

    private static String normalizedGroupJid(String value) {
        String normalized = value == null ? null : value.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || normalized.isBlank() || !normalized.endsWith("@g.us")) {
            return null;
        }
        return normalized;
    }

    private static String normalizedPhone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        return digits.length() >= 5 && digits.length() <= 20 ? digits : null;
    }

    private static String normalizedParticipantJid(String value) {
        String normalized = value == null ? null : value.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || normalized.isBlank()
                || (!normalized.endsWith("@s.whatsapp.net") && !normalized.endsWith("@lid"))) {
            throw new IllegalArgumentException("踢出成员 JID 非法");
        }
        return normalized;
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
