package com.armada.contact.task;

import com.armada.contact.task.service.ContactAccountFilterNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContactAccountFilterNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ContactAccountFilterNormalizer normalizer =
            new ContactAccountFilterNormalizer(new ObjectMapper());

    private JsonNode parse(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void nullAndBlankBecomeEmptyObject() {
        assertThat(normalizer.normalize(null)).isEqualTo("{}");
        assertThat(normalizer.normalize("   ")).isEqualTo("{}");
        assertThat(normalizer.normalize("{}")).isEqualTo("{}");
    }

    @Test
    void unknownKeysAreDropped() throws Exception {
        String out = normalizer.normalize(
                "{\"country_iso2s\":[\"cn\"],\"evil_key\":1,\"rotation_status\":\"x\"}");

        JsonNode node = parse(out);
        assertThat(node.has("countryIso2s")).isTrue();
        assertThat(node.has("evil_key")).isFalse();
        // rotation_status 不在通讯录任务的透传白名单里（设计文档 §2.7）
        assertThat(node.has("rotationStatus")).isFalse();
    }

    @Test
    void countryCodesAreUpperCasedAndDeduplicated() throws Exception {
        String out = normalizer.normalize(
                "{\"country_iso2s\":[\"cn\",\"CN\",\"my\",\"\",null]}");

        JsonNode codes = parse(out).get("countryIso2s");
        assertThat(codes).hasSize(2);
        assertThat(codes.get(0).asText()).isEqualTo("CN");
        assertThat(codes.get(1).asText()).isEqualTo("MY");
    }

    @Test
    void idArraysDropNonPositiveAndDuplicates() throws Exception {
        String out = normalizer.normalize("{\"group_ids\":[3,3,0,-1,7]}");

        JsonNode ids = parse(out).get("groupIds");
        assertThat(ids).hasSize(2);
        assertThat(ids.get(0).asLong()).isEqualTo(3L);
        assertThat(ids.get(1).asLong()).isEqualTo(7L);
    }

    @Test
    void nonPositiveRangeBoundsAreDropped() throws Exception {
        String out = normalizer.normalize(
                "{\"friend_count_min\":0,\"friend_count_max\":100,\"retention_days_min\":-3}");

        JsonNode node = parse(out);
        assertThat(node.has("friendCountMin")).isFalse();
        assertThat(node.get("friendCountMax").asInt()).isEqualTo(100);
        assertThat(node.has("retentionDaysMin")).isFalse();
    }

    @Test
    void emptyArraysAndBlankStringsAreDropped() throws Exception {
        String out = normalizer.normalize(
                "{\"group_ids\":[],\"phone\":\"  \",\"online_status\":\"online\"}");

        JsonNode node = parse(out);
        assertThat(node.has("groupIds")).isFalse();
        assertThat(node.has("phone")).isFalse();
        assertThat(node.get("onlineStatus").asText()).isEqualTo("online");
    }

    @Test
    void malformedJsonBecomesEmptyObjectInsteadOfThrowing() {
        // 归一化只做白名单收口，不做输入合法性抗辩：坏 JSON 等价于「不限定」
        assertThat(normalizer.normalize("not-json")).isEqualTo("{}");
        assertThat(normalizer.normalize("[1,2,3]")).isEqualTo("{}");
    }

    @Test
    void booleanFilterIsPreserved() throws Exception {
        String out = normalizer.normalize("{\"group_invite_allowed\":false}");

        assertThat(parse(out).get("groupInviteAllowed").asBoolean()).isFalse();
    }
}
