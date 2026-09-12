package com.armada.marketing.script;

import com.armada.marketing.converter.MarketingTemplateConverter;
import com.armada.marketing.script.service.ScriptMarketingContentService;
import com.armada.marketing.service.MarketingMessageComposer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 固定步骤身份与引用关系必须跨保存、读取和旧格式归一化保持一致。 */
class ScriptQuotedReplyContentTest {
    final ScriptMarketingContentService content = new ScriptMarketingContentService(new ObjectMapper(),
            Mappers.getMapper(MarketingTemplateConverter.class), new MarketingMessageComposer(), null, null);
    String steps(String secondId, String target) {
        return """
                [{"stepId":"first","role":"ADMIN","roleKey":"A","waitMinSeconds":0,"waitMaxSeconds":0,
                  "message":{"linkMode":1,"content":"hello"}},
                 {"stepId":"%s","replyToStepId":"%s","role":"PROMOTER","roleKey":"P","waitMinSeconds":0,"waitMaxSeconds":0,
                  "message":{"linkMode":1,"content":"reply"}}]
                """.formatted(secondId, target);
    }
    @Test void roundTripKeepsStableReplyTargets() {
        var steps = content.decode(steps("second", "first"));
        content.validateRoles(steps);
        assertThat(content.encode(steps)).contains("\"replyToStepId\":\"first\"");
    }
    @Test void selfForwardMissingAndDuplicateIdentitiesAreRejected() {
        assertThatThrownBy(() -> content.validateRoles(content.decode(steps("second", "second")))).hasMessageContaining("回复目标");
        assertThatThrownBy(() -> content.validateRoles(content.decode(steps("second", "missing")))).hasMessageContaining("回复目标");
        assertThatThrownBy(() -> content.validateRoles(content.decode(steps("first", "")))).hasMessageContaining("标识");
    }
    @Test void legacyInputsGainDeterministicIdentities() {
        String legacy = steps("second", "").replace("\"stepId\":\"first\",", "")
                .replace("\"stepId\":\"second\",", "").replace("\"replyToStepId\":\"\",", "");
        var once = content.encode(content.decode(legacy));
        assertThat(once).contains("\"stepId\":\"legacy_0\"", "\"stepId\":\"legacy_1\"");
        assertThat(content.encode(content.decode(once))).isEqualTo(once);
    }
}
