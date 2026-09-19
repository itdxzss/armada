package com.armada.platform.protocol.util;

import com.armada.platform.protocol.model.result.GroupParticipantResult;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 创建者号码只接受显式身份或同一创建者成员的号码，不根据管理员角色推测。 */
public final class GroupCreatorPhones {

    private static final Pattern PN = Pattern.compile("([0-9]+)(?:(?:\\.0)?:[0-9]+)?@s\\.whatsapp\\.net");
    private static final Pattern LID = Pattern.compile("([0-9]+)(?::[0-9]+)?@lid");

    private GroupCreatorPhones() {
    }

    /** 从 PN、显式配对号码及相同身份的成员中解析创建者；相互冲突时保持未知。 */
    public static String resolve(String creator, String explicitPhone, List<GroupParticipantResult> members) {
        String result = phone(creator);
        String explicit = phone(explicitPhone);
        if (conflicts(result, explicit)) {
            return null;
        }
        result = result == null ? explicit : result;
        String identity = identity(creator);
        if (identity == null) {
            return result;
        }
        for (GroupParticipantResult member : members) {
            if (!identity.equals(identity(member.jid()))) {
                continue;
            }
            String candidate = phone(member.phone());
            if (conflicts(result, candidate)) {
                return null;
            }
            result = result == null ? candidate : result;
        }
        return result;
    }

    /** 只解析裸号和 PN JID；LID 和群 JID 永远不是手机号。 */
    public static String phone(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.matches("[0-9]+")) {
            return trimmed;
        }
        Matcher matcher = PN.matcher(trimmed);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static String identity(String value) {
        String phone = phone(value);
        if (phone != null) {
            return phone + "@s.whatsapp.net";
        }
        Matcher matcher = LID.matcher(value == null ? "" : value.trim());
        return matcher.matches() ? matcher.group(1) + "@lid" : null;
    }

    private static boolean conflicts(String first, String second) {
        return first != null && second != null && !first.equals(second);
    }
}
