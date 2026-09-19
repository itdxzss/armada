package com.armada.task.service;

import com.armada.platform.protocol.model.result.GroupParticipantResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 清理范围不按受控身份保护，仅排除新号和最后退群的原号。 */
class JoinTaskCleanupTargetsTest {
    @Test
    void includesOtherAdminsAndOwnerButExcludesSelfOriginalAndOrdinaryMembers() {
        var members = List.of(member("1@lid", "11111", true, false),
                member("2@lid", "22222", true, false),
                member("3@lid", "33333", true, false),
                member("4@lid", "44444", false, false),
                member("5@lid", "55555", false, true));
        assertEquals(List.of("3@lid", "5@lid"), JoinTaskCleanupTargets.select(
                members, "11111", "22222").stream().map(GroupParticipantResult::jid).toList());
    }

    @Test
    void unresolvedOtherMemberCannotProveOriginalAbsent() {
        var error = assertThrows(IllegalArgumentException.class, () -> JoinTaskCleanupTargets.select(
                List.of(member("1@lid", "11111", true, false),
                        member("2@lid", null, false, false)), "11111", "22222"));
        assertTrue(error.getMessage().contains("原号身份无法确认"));
    }

    @Test
    void refusesUnresolvedSelfIdentityInsteadOfRemovingSelf() {
        assertThrows(IllegalArgumentException.class, () -> JoinTaskCleanupTargets.select(
                List.of(member("1@lid", null, true, false)), "11111", "22222"));
    }

    private GroupParticipantResult member(String jid, String phone, boolean admin, boolean owner) {
        return new GroupParticipantResult(jid, phone == null ? null : phone + "@s.whatsapp.net",
                phone, admin, owner, admin ? "admin" : "");
    }
}
