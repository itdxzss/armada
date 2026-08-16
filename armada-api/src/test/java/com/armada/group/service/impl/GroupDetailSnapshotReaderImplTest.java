package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupListCurrentMapper;
import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 群详情读取器必须保留最后一次完整成员快照口径。 */
@ExtendWith(MockitoExtension.class)
class GroupDetailSnapshotReaderImplTest {

    private static final long TENANT_ID = 7L;
    private static final long GROUP_LINK_ID = 10L;

    @Mock
    private GroupListCurrentMapper currentMapper;

    @Mock
    private GroupMetadataSyncTaskMapper taskMapper;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void membersPreserveLastCompleteSnapshotSemantics() {
        TenantContext.set(TENANT_ID);
        WhatsappGroupMemberSnapshot member = new WhatsappGroupMemberSnapshot();
        member.setParticipantJid("1001@s.whatsapp.net");
        when(currentMapper.selectGroupDetailMembers(TENANT_ID, GROUP_LINK_ID))
                .thenReturn(List.of(member));
        GroupDetailSnapshotReaderImpl reader = new GroupDetailSnapshotReaderImpl(
                currentMapper, taskMapper);

        assertThat(reader.members(GROUP_LINK_ID))
                .extracting(WhatsappGroupMemberSnapshot::getParticipantJid)
                .containsExactly("1001@s.whatsapp.net");
        verify(currentMapper).selectGroupDetailMembers(TENANT_ID, GROUP_LINK_ID);
        verifyNoInteractions(taskMapper);
    }
}
