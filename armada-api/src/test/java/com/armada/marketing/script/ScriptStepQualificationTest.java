package com.armada.marketing.script;

import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountService;
import com.armada.group.model.vo.GroupScriptCandidateVO;
import com.armada.group.service.GroupScriptCandidateService;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.dto.ScriptMarketingStepDTO;
import com.armada.marketing.model.entity.ScriptMarketingGroup;
import com.armada.marketing.script.service.ScriptMarketingContentService;
import com.armada.marketing.script.service.ScriptQualificationService;
import com.armada.shared.exception.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 运行中按当前消息独立校验；跨域事实用 test double，群进度和失败记录另由 H2 执行测试覆盖。 */
class ScriptStepQualificationTest {
    final AccountGroupService accountGroups = mock(AccountGroupService.class);
    final AccountService accounts = mock(AccountService.class);
    final GroupScriptCandidateService candidates = mock(GroupScriptCandidateService.class);
    final ScriptMarketingContentService content = mock(ScriptMarketingContentService.class);
    final ScriptQualificationService service = new ScriptQualificationService(accountGroups, accounts, candidates, content);
    final MarketingTemplateDTO message = new MarketingTemplateDTO("", 1, null, null, "hello", null, null, null, null, false);
    final ScriptMarketingGroup group = new ScriptMarketingGroup();

    ScriptStepQualificationTest() {
        group.setGroupLinkId(40L); group.setBindingsJson("original");
        when(content.decodeBindings("original")).thenReturn(Map.of("当前角色", 2L, "其他角色", 3L));
        when(content.supports(any(), any())).thenReturn(true);
    }

    @Test void healthyCurrentAccountIgnoresOtherRolesAndStartupPoolSize() {
        var current = step("PROMOTER");
        when(candidates.list(anyList(), eq(30L), anyList())).thenReturn(List.of(
                new GroupScriptCandidateVO(40L, 2L, 30L, 1, true, true, "WEB", true, false),
                new GroupScriptCandidateVO(40L, 3L, 30L, 1, false, false, "WEB", true, false),
                new GroupScriptCandidateVO(41L, 2L, 30L, 1, false, false, "WEB", false, false)));
        assertThatCode(() -> service.requireStepSendable(30L, group, current)).doesNotThrowAnyException();
        verifyNoInteractions(accountGroups, accounts);
        verify(content).supports(any(), eq(current));
        verify(candidates).list(List.of(40L), 30L, List.of(2L));
    }

    @Test void missingOriginalAccountNeverFallsBackToAnotherHealthyAccount() {
        when(candidates.list(anyList(), eq(30L), anyList())).thenReturn(List.of(
                new GroupScriptCandidateVO(40L, 3L, 30L, 1, true, true, "WEB", true, false)));
        assertThatThrownBy(() -> service.requireStepSendable(30L, group, step("PROMOTER")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ID：2").hasMessageContaining("未确认在群");
    }

    @Test void unsupportedCurrentMessageFailsWithoutChangingBindings() {
        var current = step("PROMOTER");
        when(candidates.list(anyList(), eq(30L), anyList())).thenReturn(List.of(
                new GroupScriptCandidateVO(40L, 2L, 30L, 1, true, true, "ANDROID", true, false)));
        when(content.supports(any(), eq(current))).thenReturn(false);
        assertThatThrownBy(() -> service.requireStepSendable(30L, group, current))
                .isInstanceOf(BusinessException.class).hasMessageContaining("原发送账号不支持本条消息");
    }

    @Test void recoveredAccountCanSendItsLaterMessages() {
        when(candidates.list(anyList(), eq(30L), anyList())).thenReturn(List.of(
                new GroupScriptCandidateVO(40L, 2L, 30L, 1, true, false, "WEB", true, false)))
                .thenReturn(List.of(new GroupScriptCandidateVO(40L, 2L, 30L, 1, true, true, "WEB", true, false)));
        assertThatThrownBy(() -> service.requireStepSendable(30L, group, step("PROMOTER")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("离线");
        assertThatCode(() -> service.requireStepSendable(30L, group, step("PROMOTER"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("unavailableCandidates")
    void currentAccountMustStillMeetItsOwnSendingConditions(String role, GroupScriptCandidateVO fact, String reason) {
        when(candidates.list(anyList(), eq(30L), anyList())).thenReturn(List.of(fact));
        assertThatThrownBy(() -> service.requireStepSendable(30L, group, step(role)))
                .isInstanceOf(BusinessException.class).hasMessageContaining(reason);
    }

    static Stream<Arguments> unavailableCandidates() {
        return Stream.of(
                Arguments.of("PROMOTER", new GroupScriptCandidateVO(40L, 2L, 30L, 1, true, false, "WEB", true, false), "离线"),
                Arguments.of("PROMOTER", new GroupScriptCandidateVO(40L, 2L, 30L, 2, true, true, "WEB", true, false), "未确认在群"),
                Arguments.of("PROMOTER", new GroupScriptCandidateVO(40L, 2L, 30L, 0, true, true, "WEB", true, false), "未确认在群"),
                Arguments.of("PROMOTER", new GroupScriptCandidateVO(40L, 2L, 30L, 1, false, true, "WEB", true, false), "没有群内发言权限"),
                Arguments.of("PROMOTER", new GroupScriptCandidateVO(40L, 2L, 30L, 1, null, true, "WEB", true, false), "发言权限尚未确认"),
                Arguments.of("PROMOTER", new GroupScriptCandidateVO(40L, 2L, 30L, 1, true, true, "WEB", false, false), "群已不可用"),
                Arguments.of("PROMOTER", new GroupScriptCandidateVO(40L, 2L, 99L, 1, true, true, "WEB", true, false), "已不在所选推手分组"),
                Arguments.of("ADMIN", new GroupScriptCandidateVO(40L, 2L, 30L, 1, true, true, "WEB", true, false), "已不是群管理员"));
    }

    ScriptMarketingStepDTO step(String role) {
        return new ScriptMarketingStepDTO(role, null, message, "当前角色", 10, 10);
    }
}
