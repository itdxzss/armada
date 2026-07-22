package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.entity.GroupLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupLinkRegistryServiceImplUnitTest {

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Mock
    private AccountGroupMembershipMapper membershipMapper;

    @Test
    void registerAccountObservedGroupRevivesArchivedGroupLinkMatchedByJid() {
        GroupLinkRegistryServiceImpl service =
                new GroupLinkRegistryServiceImpl(groupLinkMapper, membershipMapper);
        when(membershipMapper.selectGroupLinkIdByGroupJidIncludingDeleted("120363001@g.us"))
                .thenReturn(88L);

        Long result = service.registerAccountObservedGroup("120363001@g.us", "测试群", 1000L);

        assertThat(result).isEqualTo(88L);
        verify(membershipMapper).touchGroupLinkFromAccountSync(88L, "测试群", 1000L);
        verify(groupLinkMapper, never()).insert(org.mockito.ArgumentMatchers.any(GroupLink.class));
    }
}
