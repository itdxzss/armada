package com.armada.task.service;

import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.util.WhatsappJids;
import java.util.LinkedHashMap;
import java.util.List;

/** 仅确定清理对象，不重复检查提权结果，也不查询受控账号保护名单。 */
public final class JoinTaskCleanupTargets {
    private JoinTaskCleanupTargets() { }

    /** 排除执行新号、原号及普通成员，保留群主作为实际协议操作目标。 */
    public static List<GroupParticipantResult> select(
            List<GroupParticipantResult> members, String newPhone, String originalPhone) {
        if (members == null) throw new IllegalArgumentException("群成员名单缺失，无法建立清理名单");
        if (members.stream().noneMatch(p -> matches(p, newPhone))) {
            throw new IllegalArgumentException("清理新号身份无法确认：新号不在名单或身份未解析");
        }
        originalAbsent(members, originalPhone);
        var targets = new LinkedHashMap<String, GroupParticipantResult>();
        for (var member : members) {
            if (matches(member, newPhone) || matches(member, originalPhone)) continue;
            if (!Boolean.TRUE.equals(member.admin()) && !Boolean.TRUE.equals(member.owner())) continue;
            if (member.jid() == null || (!member.jid().endsWith("@lid")
                    && !member.jid().endsWith("@s.whatsapp.net"))) {
                throw new IllegalArgumentException("待清理管理员身份无效");
            }
            targets.putIfAbsent(member.jid(), member);
        }
        return List.copyOf(targets.values());
    }

    /** 仅对完整名单调用；存在未解析身份时不能把匹配不到原号当作已退群。 */
    static boolean originalAbsent(List<GroupParticipantResult> members, String originalPhone) {
        if (members.stream().anyMatch(p -> matches(p, originalPhone))) return false;
        boolean unresolved = members.stream().anyMatch(p ->
                (p.jid() == null || !p.jid().matches("[0-9]+@s\\.whatsapp\\.net"))
                && (p.pnJid() == null || !p.pnJid().matches("[0-9]+@s\\.whatsapp\\.net"))
                && (p.phone() == null || !p.phone().matches("\\+?[0-9]+")));
        if (unresolved) throw new IllegalArgumentException("原号身份无法确认：成员身份未解析，不能判定已退群");
        return true;
    }

    private static boolean matches(GroupParticipantResult member, String phone) {
        String jid = WhatsappJids.userJid(phone);
        return jid.equals(member.jid()) || jid.equals(member.pnJid())
                || (member.phone() != null && member.phone().matches("[+0-9]+")
                    && jid.equals(WhatsappJids.userJid(member.phone())));
    }
}
