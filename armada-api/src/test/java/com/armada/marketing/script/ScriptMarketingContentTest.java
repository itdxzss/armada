package com.armada.marketing.script;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.marketing.converter.MarketingTemplateConverter;
import com.armada.marketing.model.ButtonType;
import com.armada.marketing.model.MessageButton;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.dto.ScriptMarketingStepDTO;
import com.armada.marketing.script.service.ScriptMarketingContentService;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 文本/按钮配置、固定身份和 JSON 顺序；素材 SQL 在 ResourceAssetMapperH2Test 独立验证。 */
class ScriptMarketingContentTest {
    final AccountProtocolLookupService accounts = mock(AccountProtocolLookupService.class);
    final ScriptMarketingContentService service = new ScriptMarketingContentService(new ObjectMapper(),
            Mappers.getMapper(MarketingTemplateConverter.class), new MarketingMessageComposer(), null, accounts);
    ScriptMarketingContentTest() {
        when(accounts.findActiveProtocolRef(anyLong())).thenAnswer(call -> Optional.of(
                new ProtocolAccountRef(call.getArgument(0), ProtocolBackend.WEB, "account", "15550000000")));
    }
    @Test void minimumRolesAndRepeatedRoleAccountAreEnforcedWithoutChangingOrder() {
        var admin = step("ADMIN", 1L); var promoter = step("PROMOTER", 2L);
        var steps = List.of(admin, promoter, admin);
        service.validate(steps);
        var decoded = service.decode(service.encode(steps));
        assertThat(decoded).zipSatisfy(steps, (actual, expected) ->
                assertThat(actual).usingRecursiveComparison().ignoringFields("stepId").isEqualTo(expected));
        assertThat(decoded).extracting(ScriptMarketingStepDTO::stepId).containsExactly("legacy_0", "legacy_1", "legacy_2");
        assertThat(service.decode(service.encode(decoded))).isEqualTo(decoded);
        assertThatThrownBy(() -> service.validate(List.of(admin, step("PROMOTER", 1L))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("角色必须一致");
        assertThatThrownBy(() -> service.validate(List.of(admin, admin)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不同账号");
    }
    @Test void inaccessibleAccountIsRejectedBeforeTaskIsSaved() {
        when(accounts.findActiveProtocolRef(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.validate(List.of(step("ADMIN", 1L), step("PROMOTER", 2L))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("账号不存在");
    }
    @Test void buttonsUseActualMarketingComposerAndMalformedLinkIsRejected() {
        var dto = new MarketingTemplateDTO("", 2, null, null, "welcome", "details",
                List.of(new MessageButton(ButtonType.LINK_JUMP, "open", "https://example.com")), null, null, false);
        var payload = service.payload(new ScriptMarketingStepDTO("ADMIN", 1L, dto, null, null, null, null, null));
        assertThat(payload.content().buttonCard().buttons()).singleElement()
                .satisfies(button -> assertThat(button.value()).isEqualTo("https://example.com"));
        var invalid = new MarketingTemplateDTO("", 1, null, null, "hello", null,
                null, "javascript:alert(1)", null, false);
        assertThatThrownBy(() -> service.payload(new ScriptMarketingStepDTO("ADMIN", 1L, invalid, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("http(s)");
    }
    @Test void refusesMoreThanThreeButtonsBeforeDispatch() {
        var button = new MessageButton(ButtonType.QUICK_REPLY, "reply", null);
        var message = new MarketingTemplateDTO("", 2, null, null, "hello", null,
                List.of(button, button, button, button), null, null, false);
        assertThatThrownBy(() -> service.payload(new ScriptMarketingStepDTO("ADMIN", 1L, message, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("1–3");
    }
    @Test void androidAccountRejectsUnsupportedButtonsBeforeSavingButKeepsWebCapabilities() {
        var link = new MessageButton(ButtonType.LINK_JUMP, "open", "https://example.com");
        var copy = new MessageButton(ButtonType.COPY_CONTENT, "copy", "code");
        var quick = new MessageButton(ButtonType.QUICK_REPLY, "reply", null);
        for (var buttons : List.of(List.of(link, link), List.of(copy), List.of(quick))) {
            var message = new MarketingTemplateDTO("", 2, null, null, "hello", null,
                    buttons, null, null, false);
            var steps = List.of(new ScriptMarketingStepDTO("ADMIN", 1L, message, null, null, null, null, null), step("PROMOTER", 2L));
            when(accounts.findActiveProtocolRef(1L)).thenReturn(Optional.of(
                    new ProtocolAccountRef(1L, ProtocolBackend.ANDROID, "account", "15550000000")));
            assertThatThrownBy(() -> service.validate(steps))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("Android");
            when(accounts.findActiveProtocolRef(1L)).thenReturn(Optional.of(
                    new ProtocolAccountRef(1L, ProtocolBackend.WEB, "account", "15550000000")));
            service.validate(steps);
        }
        when(accounts.findActiveProtocolRef(1L)).thenReturn(Optional.of(
                new ProtocolAccountRef(1L, ProtocolBackend.ANDROID, "account", "15550000000")));
        var message = new MarketingTemplateDTO("", 2, null, null, "hello", null,
                List.of(link), null, null, false);
        service.validate(List.of(new ScriptMarketingStepDTO("ADMIN", 1L, message, null, null, null, null, null), step("PROMOTER", 2L)));
    }
    ScriptMarketingStepDTO step(String role, Long id) {
        return new ScriptMarketingStepDTO(role, id, new MarketingTemplateDTO("", 1, null, null,
                "hello", "body", null, null, null, false), null, null, null, null, null);
    }
}
