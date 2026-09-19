package com.armada.platform.protocol.util;

import static org.assertj.core.api.Assertions.assertThat;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class GroupCreatorPhonesTest {
    @Test
    void acceptsDevicePnAndExactOrdinaryCreatorButNotUnrelatedSuperadmin() {
        assertThat(GroupCreatorPhones.resolve("2348083697499.0:7@s.whatsapp.net", null, List.of()))
                .isEqualTo("2348083697499");
        GroupParticipantResult creator = new GroupParticipantResult(
                "47970506555552@lid", null, "2348083697499", false, false, "participant");
        assertThat(GroupCreatorPhones.resolve("47970506555552:8@lid", null, List.of(creator)))
                .isEqualTo("2348083697499");
        GroupParticipantResult other = new GroupParticipantResult(
                "2348083697499@s.whatsapp.net", null, "2348083697499", true, true, "superadmin");
        assertThat(GroupCreatorPhones.resolve("47970506555552@lid", null, List.of(other))).isNull();
        assertThat(GroupCreatorPhones.resolve(null, null, List.of(other))).isNull();
    }

    @Test
    void conflictingExplicitAndMatchingParticipantPhonesRemainUnknown() {
        assertThat(GroupCreatorPhones.resolve("2348083697499@s.whatsapp.net", "919000000001", List.of()))
                .isNull();
        GroupParticipantResult creator = new GroupParticipantResult(
                "47970506555552@lid", null, "919000000001", false, false, null);
        assertThat(GroupCreatorPhones.resolve("47970506555552@lid", "2348083697499", List.of(creator)))
                .isNull();
    }

    @Test
    void refusesLidGroupIdAndMalformedPhone() {
        for (String value : new String[]{"47970506555552@lid", "2348083697499-123@g.us",
                "2348083697499:no@s.whatsapp.net", "123@@s.whatsapp.net"}) {
            assertThat(GroupCreatorPhones.phone(value)).isNull();
        }
    }
}
