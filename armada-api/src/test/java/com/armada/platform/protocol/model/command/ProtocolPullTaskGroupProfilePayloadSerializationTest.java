package com.armada.platform.protocol.model.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「群信息设置」命令载荷的序列化契约：留空的设置项必须整个字段消失。
 *
 * <p>为什么单独钉这一条：hydrator 里把字段置 {@code null} 只完成了一半，Jackson 默认会把它
 * 序列化成 {@code "subject": null} 发出去。协议端拿到一个显式 {@code null} 无从判断是「这项
 * 别动」还是「把这项清空」，按清空执行就把客户老群里自己配的群名、描述、头像抹了。因此
 * 「留空」的线上表达必须是字段不出现，而不是 null。</p>
 */
class ProtocolPullTaskGroupProfilePayloadSerializationTest {

    /** 用默认 ObjectMapper：契约必须由载荷类自己保证，不能依赖某个环境的全局 Jackson 配置。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("留空的设置项字段整个不出现，不输出 null")
    void emptySettingItemsDisappearFromJson() {
        JsonNode json = objectMapper.valueToTree(payload(null, null, null, null, null));

        assertThat(json.has("subject")).isFalse();
        assertThat(json.has("avatar")).isFalse();
        assertThat(json.has("description")).isFalse();
        assertThat(json.has("sendMessagesAllowed")).isFalse();
        assertThat(json.has("editGroupSettingsAllowed")).isFalse();
        assertThat(json.has("addMembersAllowed")).isFalse();
        assertThat(json.has("joinApprovalEnabled")).isFalse();
        assertThat(json.has("ephemeralDurationSeconds")).isFalse();
    }

    @Test
    @DisplayName("序列化后的 JSON 文本里不含任何 null 字面量")
    void serializedJsonNeverCarriesNullLiteral() throws Exception {
        String json = objectMapper.writeValueAsString(payload(null, null, null, null, null));

        assertThat(json).doesNotContain("null");
    }

    @Test
    @DisplayName("填了值的设置项照常出现，头像按 base64 与 mimetype 两段下发")
    void filledSettingItemsAreSerialized() {
        JsonNode json = objectMapper.valueToTree(payload(
                "客户群",
                new ProtocolPullTaskGroupProfilePayload.Avatar("YmFzZTY0", "image/png"),
                "本群仅发布客户通知",
                Boolean.FALSE,
                604_800));

        assertThat(json.path("subject").asText()).isEqualTo("客户群");
        assertThat(json.path("avatar").path("base64").asText()).isEqualTo("YmFzZTY0");
        assertThat(json.path("avatar").path("mimetype").asText()).isEqualTo("image/png");
        assertThat(json.path("description").asText()).isEqualTo("本群仅发布客户通知");
        assertThat(json.path("sendMessagesAllowed").asBoolean()).isFalse();
        assertThat(json.path("ephemeralDurationSeconds").asInt()).isEqualTo(604_800);
    }

    @Test
    @DisplayName("路由与定位事实照常出现，群 JID 必填")
    void routingFactsAreAlwaysSerialized() {
        JsonNode json = objectMapper.valueToTree(payload(null, null, null, null, null));

        assertThat(json.path("tenantId").asLong()).isEqualTo(7L);
        assertThat(json.path("groupJid").asText()).isEqualTo("120363group@g.us");
        // 字段名必须是 wsPhone：协议侧 coordinator 按它做 group-action 族路由。
        assertThat(json.path("wsPhone").asText()).isEqualTo("8613800000901");
        assertThat(json.path("source").asText())
                .isEqualTo(ProtocolPullTaskGroupProfileCommandRequest.SOURCE);
    }

    private static ProtocolPullTaskGroupProfilePayload payload(
            String subject,
            ProtocolPullTaskGroupProfilePayload.Avatar avatar,
            String description,
            Boolean sendMessagesAllowed,
            Integer ephemeralDurationSeconds) {
        return new ProtocolPullTaskGroupProfilePayload(
                7L, 100L, 11L, 811L, 901L, "manager-901", "8613800000901", "WEB",
                "120363group@g.us", 2, 30_000,
                ProtocolPullTaskGroupProfileCommandRequest.SOURCE,
                subject, avatar, description, sendMessagesAllowed, null, null, null,
                ephemeralDurationSeconds);
    }
}
