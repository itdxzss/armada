package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** WhatsApp 群成员状态时序更新的 MySQL SQL 契约。 */
class WhatsappGroupMemberMapperSqlTest {

    private static final String MAPPER_XML = "/mapper/group/WhatsappGroupMemberMapper.xml";

    @Test
    void groupWritesAreSerializedByTheGroupLinkRow() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(block(xml, "select", "lockGroupLink"))
                .contains("FROM group_link")
                .contains("id = #{groupLinkId}")
                .contains("FOR UPDATE");
    }

    @Test
    void preciseEventsUseOneDeterministicWinnerForTheWholeCurrentRow() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String upsert = block(xml, "sql", "incomingFactWins")
                + block(xml, "insert", "upsertMember");

        assertThat(upsert)
                .contains("VALUES(status_updated_at) &gt; whatsapp_group_member.status_updated_at")
                .contains("WHEN 'PARTICIPANT_REMOVE' THEN 3")
                .contains("WHEN 'PARTICIPANT_LEAVE' THEN 4")
                .contains("WHEN 'PARTICIPANT_ADD' THEN 2")
                .contains("WHEN 'MEMBER_SNAPSHOT' THEN 1")
                .contains("VALUES(status_source_event_id) &gt; whatsapp_group_member.status_source_event_id")
                .contains("VALUES(status_source) = 'PARTICIPANT_ADD'")
                .contains("THEN VALUES(joined_at)")
                .contains("VALUES(membership_status) IN (3, 4)")
                .contains("THEN VALUES(last_exit_type)")
                .contains("THEN VALUES(last_exited_at)")
                .contains("status_source_event_id = CASE WHEN")
                .contains("status_updated_at = CASE WHEN");
        assertThat(upsert.indexOf("observer_account_id = CASE WHEN"))
                .isLessThan(upsert.indexOf("status_source_event_id = CASE WHEN"));
        assertThat(upsert.indexOf("status_source_event_id = CASE WHEN"))
                .isLessThan(upsert.indexOf("status_source = CASE WHEN"));
        assertThat(upsert.indexOf("status_source = CASE WHEN"))
                .isLessThan(upsert.indexOf("status_updated_at = CASE WHEN"));
    }

    @Test
    void completeSnapshotOnlyMarksOlderCurrentRowsMissing() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String select = block(xml, "select", "selectMissingCurrentMembers");
        String update = block(xml, "update", "markMissingMembers");

        assertThat(select)
                .contains("FROM whatsapp_group_member_fact fact")
                .contains("ranked.membership_status = 1")
                .contains("ranked.occurred_at &lt; #{statusUpdatedAt}")
                .contains("ranked.source_event_id &lt; #{sourceEventId}")
                .contains("ranked.member_jid NOT IN");
        assertThat(update)
                .contains("membership_status = 5")
                .contains("status_source = 'MEMBER_SNAPSHOT'")
                .contains("status_source_event_id = #{sourceEventId}")
                .contains("membership_status = 1")
                .contains("status_updated_at &lt; #{statusUpdatedAt}")
                .contains("status_source_event_id &lt; #{sourceEventId}");
    }

    private static String block(String xml, String tag, String id) {
        String startTag = "<" + tag + " id=\"" + id + "\"";
        int start = xml.indexOf(startTag);
        assertThat(start).as("mapper %s %s exists", tag, id).isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</" + tag + ">", start);
        assertThat(end).as("mapper %s %s closes", tag, id).isGreaterThan(start);
        return xml.substring(start, end);
    }
}
