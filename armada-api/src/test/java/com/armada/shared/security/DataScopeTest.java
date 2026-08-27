package com.armada.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

class DataScopeTest {

    @Test
    void userScopesKeepActorAsCreateOwner() {
        DataScope self = DataScope.self(7L);
        DataScope all = DataScope.all(8L);

        assertThat(self.mode()).isEqualTo(DataScopeMode.SELF);
        assertThat(self.actorUserId()).isEqualTo(7L);
        assertThat(self.ownerUserIdForCreate()).isEqualTo(7L);
        assertThat(all.mode()).isEqualTo(DataScopeMode.ALL);
        assertThat(all.actorUserId()).isEqualTo(8L);
        assertThat(all.ownerUserIdForCreate()).isEqualTo(8L);
    }

    @Test
    void systemScopeRequiresReasonAndCannotProvideCreateOwner() {
        DataScope system = DataScope.system(" account import dispatcher ");

        assertThat(system.mode()).isEqualTo(DataScopeMode.SYSTEM);
        assertThat(system.actorUserId()).isNull();
        assertThat(system.systemReason()).isEqualTo("account import dispatcher");
        assertThatIllegalStateException()
                .isThrownBy(system::ownerUserIdForCreate)
                .withMessageContaining("SYSTEM");
        assertThatIllegalArgumentException().isThrownBy(() -> DataScope.system(null));
        assertThatIllegalArgumentException().isThrownBy(() -> DataScope.system("  "));
    }

    @Test
    void userScopesRejectInvalidActorAndSystemReason() {
        assertThatIllegalArgumentException().isThrownBy(() -> DataScope.self(0L));
        assertThatIllegalArgumentException().isThrownBy(() -> DataScope.all(-1L));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DataScope(DataScopeMode.SELF, 7L, "not-system"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DataScope(DataScopeMode.SYSTEM, 7L, "scheduler"));
    }
}
