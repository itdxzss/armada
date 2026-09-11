package com.armada.marketing.script;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.marketing.converter.MarketingTemplateConverter;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.dto.ScriptMarketingStepDTO;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.marketing.script.service.ScriptMarketingContentService;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.platform.protocol.model.enums.MessageType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 复现纯图片经角色校验、快照和真实消息组装器后丢失可发送资格的回归。 */
class ScriptMarketingImageContentTest {
    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png"})
    void imageOnlyDefinitionAndTaskRetainEmptyCaptionAndImagePayload(String contentType) {
        var files = mock(MarketingTemplateFileMapper.class);
        var image = new MarketingTemplateFile();
        image.setContent(new byte[] {1, 2, 3});
        image.setContentType(contentType);
        when(files.selectById(34L)).thenReturn(image);
        var service = new ScriptMarketingContentService(new ObjectMapper(),
                Mappers.getMapper(MarketingTemplateConverter.class), new MarketingMessageComposer(),
                files, mock(AccountProtocolLookupService.class));
        var message = new MarketingTemplateDTO("image-only", LinkMode.IMAGE_TEXT.code(), null, 34L,
                "", "", List.of(), "", null, false);
        var definition = List.of(
                new ScriptMarketingStepDTO("ADMIN", null, message, "admin", 0, 0),
                new ScriptMarketingStepDTO("PROMOTER", null, message, "p1", 3, 3));

        service.validateRoles(definition);
        var snapshot = service.decode(service.encode(definition));
        var taskSteps = List.of(new ScriptMarketingStepDTO("ADMIN", 685L, snapshot.get(0).message(),
                "admin", 0, 0), snapshot.get(1));
        service.validateRoles(taskSteps);
        var payload = service.payload(taskSteps.get(0));

        assertThat(snapshot).isEqualTo(definition);
        assertThat(payload.type()).isEqualTo(MessageType.IMAGE);
        assertThat(payload.content().text()).isEmpty();
        assertThat(payload.content().image().bytes()).containsExactly(1, 2, 3);
        assertThat(payload.content().image().mimetype()).isEqualTo(contentType);
    }
}
