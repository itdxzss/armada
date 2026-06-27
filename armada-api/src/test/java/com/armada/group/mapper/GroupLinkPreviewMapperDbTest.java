package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** GroupLinkPreviewMapper 真库测试:验 upsert 和按 group_link_id 查询。 */
class GroupLinkPreviewMapperDbTest extends DbTestBase {

    @Autowired
    private GroupLinkMapper groupLinkMapper;

    @Autowired
    private GroupLinkPreviewMapper previewMapper;

    @Test
    void upsert_insertsAndUpdatesUniquePreviewRow() {
        GroupLink link = insertLink("chat.whatsapp.com/PreviewRoundTrip");
        long now = System.currentTimeMillis();

        GroupLinkPreview row = new GroupLinkPreview();
        row.setGroupLinkId(link.getId());
        row.setGroupJid("120363-preview@g.us");
        row.setInviteCode("PreviewRoundTrip");
        row.setWaSubject("预览群");
        row.setMemberSize(12);
        row.setOwnerPhone("919999999999");
        row.setAnnounceOnly(Boolean.TRUE);
        row.setAvatarUrl("https://example.test/avatar.jpg");
        row.setLastPreviewAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);

        assertThat(previewMapper.upsert(row)).isEqualTo(1);
        GroupLinkPreview inserted = previewMapper.selectByGroupLinkId(link.getId());
        assertThat(inserted).isNotNull();
        assertThat(inserted.getGroupJid()).isEqualTo("120363-preview@g.us");
        assertThat(inserted.getWaSubject()).isEqualTo("预览群");
        assertThat(inserted.getMemberSize()).isEqualTo(12);
        assertThat(inserted.getAnnounceOnly()).isTrue();

        GroupLinkPreview update = new GroupLinkPreview();
        update.setGroupLinkId(link.getId());
        update.setGroupJid("120363-preview-updated@g.us");
        update.setInviteCode("PreviewRoundTrip");
        update.setWaSubject("预览群-更新");
        update.setMemberSize(18);
        update.setOwnerPhone("918888888888");
        update.setAnnounceOnly(Boolean.FALSE);
        update.setAvatarUrl("https://example.test/avatar2.jpg");
        update.setLastPreviewAt(now + 1_000);
        update.setCreatedAt(now);
        update.setUpdatedAt(now + 1_000);

        previewMapper.upsert(update);
        GroupLinkPreview updated = previewMapper.selectByGroupLinkId(link.getId());
        assertThat(updated.getId()).isEqualTo(inserted.getId());
        assertThat(updated.getGroupJid()).isEqualTo("120363-preview-updated@g.us");
        assertThat(updated.getWaSubject()).isEqualTo("预览群-更新");
        assertThat(updated.getMemberSize()).isEqualTo(18);
        assertThat(updated.getAnnounceOnly()).isFalse();
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
