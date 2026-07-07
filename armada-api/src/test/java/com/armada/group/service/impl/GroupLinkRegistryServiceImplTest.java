package com.armada.group.service.impl;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class GroupLinkRegistryServiceImplTest extends DbTestBase {

    @Autowired
    private GroupLinkRegistryService service;

    @Autowired
    private GroupLinkMapper groupLinkMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void registerSelfBuiltGroupCreatesWaGroupLinkAndOwnerMembership() {
        Long groupLinkId = service.registerSelfBuiltGroup(
                "120363new@g.us",
                "任务群-1",
                7L,
                "8613900000000",
                51,
                1000L);

        assertThat(groupLinkId).isNotNull();
        GroupLink link = groupLinkMapper.selectAnyByUrl("wa://group/120363new@g.us");
        assertThat(link.getId()).isEqualTo(groupLinkId);
        assertThat(link.getOrigin()).isEqualTo(GroupLinkOrigin.SELF_BUILT.code());
        assertThat(link.getMembershipState()).isEqualTo(GroupMembershipState.OWNER.code());

        String groupJid = jdbc.queryForObject(
                "SELECT group_jid FROM group_link_preview WHERE group_link_id = ?",
                String.class,
                groupLinkId);
        assertThat(groupJid).isEqualTo("120363new@g.us");

        Integer admin = jdbc.queryForObject(
                "SELECT is_admin FROM account_group_membership WHERE account_id = ? AND group_jid = ?",
                Integer.class,
                7L,
                "120363new@g.us");
        assertThat(admin).isEqualTo(1);
    }
}
