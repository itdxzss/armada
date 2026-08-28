package com.armada.account.contact.service;

import com.armada.account.contact.model.NormalizedContacts;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通讯录协议快照归一化器。
 *
 * <p>协议 adapter 已做过一次号码归一，本类只负责去重、裁空、派生计数，不重复解析 JID。</p>
 */
@Component
public class AccountContactNormalizer {

    private static final String USER_SERVER = "@s.whatsapp.net";

    /**
     * 把协议快照归一为可落库的行与计数。
     *
     * @param snapshot 协议通讯录快照，允许为 null
     * @return 归一结果，输入为空时返回 {@link NormalizedContacts#EMPTY}
     */
    public NormalizedContacts normalize(AccountContactSnapshot snapshot) {
        if (snapshot == null || snapshot.contacts() == null || snapshot.contacts().isEmpty()) {
            return NormalizedContacts.EMPTY;
        }
        Map<String, NormalizedContacts.Row> byPhone = new LinkedHashMap<>();
        for (AccountContactSnapshot.Contact contact : snapshot.contacts()) {
            if (contact == null) {
                continue;
            }
            String phone = digits(contact.phone());
            if (phone == null) {
                continue;
            }
            NormalizedContacts.Row prev = byPhone.get(phone);
            String fullName = coalesce(text(contact.fullName()), prev == null ? null : prev.fullName());
            String firstName = coalesce(text(contact.firstName()), prev == null ? null : prev.firstName());
            String pushName = coalesce(text(contact.pushName()), prev == null ? null : prev.pushName());
            String businessName =
                    coalesce(text(contact.businessName()), prev == null ? null : prev.businessName());
            byPhone.put(phone, new NormalizedContacts.Row(
                    phone,
                    coalesce(text(contact.jid()), phone + USER_SERVER),
                    fullName,
                    firstName,
                    pushName,
                    businessName,
                    fullName != null || firstName != null,
                    // 双向好友标记两套协议都不暴露（设计文档 §5.1 待验证项 V2），恒为 false。
                    // 协议补齐后只需改这一处。
                    false));
        }
        List<NormalizedContacts.Row> rows = List.copyOf(new ArrayList<>(byPhone.values()));
        int namedNum = (int) rows.stream().filter(NormalizedContacts.Row::named).count();
        int mutualNum = (int) rows.stream().filter(NormalizedContacts.Row::mutual).count();
        return new NormalizedContacts(rows, rows.size(), namedNum, mutualNum);
    }

    private static String digits(String value) {
        String trimmed = text(value);
        if (trimmed == null) {
            return null;
        }
        return trimmed.chars().allMatch(Character::isDigit) ? trimmed : null;
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String coalesce(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }
}
