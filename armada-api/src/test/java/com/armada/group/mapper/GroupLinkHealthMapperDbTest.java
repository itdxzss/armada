package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.enums.GroupLinkHealthStatus;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** GroupLinkHealthMapper 真库测试:验 upsert 和按 group_link_id 查询。 */
class GroupLinkHealthMapperDbTest extends DbTestBase {

    @Autowired
    private GroupLinkMapper groupLinkMapper;

    @Autowired
    private GroupLinkHealthMapper healthMapper;

    @Test
    void upsert_insertsAndUpdatesUniqueHealthRow() {
        GroupLink link = insertLink("chat.whatsapp.com/HealthRoundTrip");
        long now = System.currentTimeMillis();

        GroupLinkHealth row = new GroupLinkHealth();
        row.setGroupLinkId(link.getId());
        row.setHealthStatus(GroupLinkHealthStatus.AVAILABLE.code());
        row.setBanned(Boolean.FALSE);
        row.setCurrentCount(20);
        row.setLastCheckAt(now);
        row.setLastHealthError(null);
        row.setHealthFailureCount(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);

        assertThat(healthMapper.upsert(row)).isEqualTo(1);
        GroupLinkHealth inserted = healthMapper.selectByGroupLinkId(link.getId());
        assertThat(inserted).isNotNull();
        assertThat(inserted.getHealthStatus()).isEqualTo(GroupLinkHealthStatus.AVAILABLE.code());
        assertThat(inserted.getBanned()).isFalse();
        assertThat(inserted.getCurrentCount()).isEqualTo(20);

        GroupLinkHealth update = new GroupLinkHealth();
        update.setGroupLinkId(link.getId());
        update.setHealthStatus(GroupLinkHealthStatus.LINK_INVALID.code());
        update.setBanned(Boolean.TRUE);
        update.setCurrentCount(0);
        update.setLastCheckAt(now + 1_000);
        update.setLastHealthError("INVITE_REVOKED");
        update.setHealthFailureCount(2);
        update.setCreatedAt(now);
        update.setUpdatedAt(now + 1_000);

        healthMapper.upsert(update);
        GroupLinkHealth updated = healthMapper.selectByGroupLinkId(link.getId());
        assertThat(updated.getId()).isEqualTo(inserted.getId());
        assertThat(updated.getHealthStatus()).isEqualTo(GroupLinkHealthStatus.LINK_INVALID.code());
        assertThat(updated.getBanned()).isTrue();
        assertThat(updated.getCurrentCount()).isEqualTo(0);
        assertThat(updated.getLastHealthError()).isEqualTo("INVITE_REVOKED");
        assertThat(updated.getHealthFailureCount()).isEqualTo(2);
    }

    private GroupLink insertLink(String url) {
        GroupLink link = new GroupLink();
        link.setLinkUrl(url);
        long now = System.currentTimeMillis();
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        groupLinkMapper.insert(link);
        return link;
    }
}
