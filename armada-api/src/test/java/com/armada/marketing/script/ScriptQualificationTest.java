package com.armada.marketing.script;

import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountService;
import com.armada.group.model.vo.GroupScriptCandidateVO;
import com.armada.group.service.GroupScriptCandidateService;
import com.armada.marketing.model.ButtonType;
import com.armada.marketing.model.MessageButton;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.dto.ScriptMarketingStepDTO;
import com.armada.marketing.model.entity.ScriptMarketingGroup;
import com.armada.marketing.script.service.ScriptMarketingContentService;
import com.armada.marketing.script.service.ScriptQualificationService;
import com.armada.shared.response.PageResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 业务资格规则与真实群查询分开验证，mock 仅用于跨域 Service 边界。 */
class ScriptQualificationTest {
    final AccountService accounts = mock(AccountService.class);
    final GroupScriptCandidateService candidates = mock(GroupScriptCandidateService.class);
    final ScriptMarketingContentService content = mock(ScriptMarketingContentService.class);
    final ScriptQualificationService service = new ScriptQualificationService(mock(AccountGroupService.class), accounts, candidates, content);
    final MarketingTemplateDTO message = new MarketingTemplateDTO("", 1, null, null, "hello", null, null, null, null, false);

    ScriptQualificationTest() {
        when(accounts.listAccounts(any())).thenReturn(PageResult.of(List.of(), 1, 1, 6));
        when(content.supports(any(), any())).thenReturn(true);
    }
    @Test void fiveRolesNeedFiveDistinctInGroupAccountsAndOneShortGroupBlocksEntireTask() {
        var rows = new ArrayList<GroupScriptCandidateVO>();
        rows.add(fact(40L, 1L, 99L, 1, true, true));
        rows.add(fact(41L, 1L, 99L, 1, true, true));
        for (long id = 2; id <= 6; id++) rows.add(fact(40L, id, 30L, 1, true, true));
        rows.add(fact(41L, 2L, 30L, 1, true, true));
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(rows);
        var result = service.inspect(30L, steps(5), List.of(group(40L), group(41L)), false);
        assertThat(result.report().ready()).isFalse();
        assertThat(result.report().groups()).hasSize(2);
        assertThat(result.report().groups().get(0).ready()).isTrue();
        assertThat(result.report().groups().get(1).available()).isEqualTo(1);
        assertThat(result.report().groups().get(1).shortage()).isEqualTo(4);
    }
    @Test void poolSizeUsesStrictlyGreaterWhileEachGroupUsesAtLeastRequired() {
        when(accounts.listAccounts(any())).thenReturn(PageResult.of(List.of(), 1, 1, 1));
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(List.of(
                fact(40L, 1L, 99L, 1, true, true), fact(40L, 2L, 30L, 1, true, true)));
        var result = service.inspect(30L, steps(1), List.of(group(40L)), false);
        assertThat(result.report().ready()).isFalse();
        assertThat(result.report().poolReason()).contains("至少 2", "当前 1");
        assertThat(result.report().groups().get(0).shortage()).isZero();
    }
    @Test void offlineUnknownAndNoPermissionAreSeparateAndNeverCountedAsAvailable() {
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(List.of(
                fact(40L, 1L, 99L, 1, true, true),
                fact(40L, 2L, 30L, 1, true, false),
                fact(40L, 3L, 30L, 0, null, true),
                fact(40L, 4L, 30L, 1, false, true),
                fact(40L, 5L, 30L, 2, null, true)));
        var row = service.inspect(30L, steps(1), List.of(group(40L)), false).report().groups().get(0);
        assertThat(row.available()).isZero();
        assertThat(row.offline()).isEqualTo(1);
        assertThat(row.noPermission()).isEqualTo(1);
        assertThat(row.unconfirmed()).isEqualTo(1);
        assertThat(row.ready()).isFalse();
    }
    @Test void recheckAfterJoiningCanPassButResumeNeverReplacesAnOriginalAccount() {
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(List.of(
                fact(40L, 1L, 99L, 1, true, true), fact(40L, 3L, 30L, 1, true, true)));
        assertThat(service.inspect(30L, steps(1), List.of(group(40L)), false).report().ready()).isTrue();
        var group = group(40L); group.setBindingsJson("original");
        when(content.decodeBindings("original")).thenReturn(Map.of("A", 1L, "P1", 2L));
        var result = service.inspect(30L, steps(1), List.of(group), true);
        assertThat(result.report().ready()).isFalse();
        assertThat(result.bindings()).isEmpty();
        assertThat(result.report().groups().get(0).reasons()).anyMatch(reason -> reason.contains("原绑定"));
    }
    List<ScriptMarketingStepDTO> steps(int count) {
        var steps = new ArrayList<ScriptMarketingStepDTO>();
        steps.add(new ScriptMarketingStepDTO("ADMIN", 1L, message, "A", 0, 0, null, null));
        for (int i = 1; i <= count; i++) steps.add(new ScriptMarketingStepDTO("PROMOTER", null, message, "P" + i, 10, 20, null, null));
        steps.add(steps.get(1)); // 同一推手重复发言不增加所需人数
        return steps;
    }
    @Test void automaticAdminIsChosenPerGroupAndAllAdminRolesShareOneAccount() {
        var steps = new ArrayList<>(steps(1));
        steps.set(0, new ScriptMarketingStepDTO("ADMIN", null, message, "A", 0, 0, null, null));
        steps.add(new ScriptMarketingStepDTO("ADMIN", null, message, "主持人", 1, 2, null, null));
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(List.of(
                fact(40L, 1L, 99L, 1, true, true), fact(40L, 2L, 30L, 1, true, true),
                fact(41L, 1L, 99L, 1, true, false), fact(41L, 2L, 30L, 1, true, true)));
        var result = service.inspect(30L, steps, List.of(group(40L)), false);
        assertThat(result.report().ready()).isTrue();
        assertThat(result.bindings().get(40L)).containsEntry("A", 1L).containsEntry("主持人", 1L).containsEntry("P1", 2L);
        var shortage = service.inspect(30L, steps, List.of(group(40L), group(41L)), false);
        assertThat(shortage.report().ready()).isFalse();
        assertThat(shortage.report().groups().get(1).reasons()).anyMatch(reason -> reason.contains("本群没有可用管理员"));
    }
    @Test void automaticAdminCannotTakeTheOnlyPusherAndDifferentGroupsChooseTheirOwnAdmin() {
        var steps = automaticSteps();
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(List.of(
                adminFact(40L, 1L, 30L), adminFact(40L, 7L, 99L),
                adminFact(41L, 8L, 99L), fact(41L, 2L, 30L, 1, true, true)));
        var result = service.inspect(30L, steps, List.of(group(40L), group(41L)), false);
        assertThat(result.report().ready()).isTrue();
        assertThat(result.bindings().get(40L)).containsEntry("A", 7L).containsEntry("P1", 1L);
        assertThat(result.bindings().get(41L)).containsEntry("A", 8L).containsEntry("P1", 2L);
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(List.of(adminFact(40L, 1L, 30L)));
        assertThat(service.inspect(30L, steps, List.of(group(40L)), false).report().ready()).isFalse();
    }
    @Test void automaticAdminRequiresActualAdminRoleAndAllOfItsMessagesMustBeSupported() {
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(List.of(
                fact(40L, 2L, 30L, 1, true, true), fact(40L, 3L, 99L, 1, true, true)));
        assertThat(service.inspect(30L, automaticSteps(), List.of(group(40L)), false).report().groups().get(0).reasons())
                .anyMatch(reason -> reason.contains("本群没有可用管理员"));
        var steps = automaticSteps();
        var secondAdmin = new ScriptMarketingStepDTO("ADMIN", null, message, "主持人", 1, 2, null, null);
        steps.add(secondAdmin);
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(List.of(
                adminFact(40L, 1L, 99L), fact(40L, 2L, 30L, 1, true, true)));
        when(content.supports(any(), org.mockito.ArgumentMatchers.eq(secondAdmin))).thenReturn(false);
        assertThat(service.inspect(30L, steps, List.of(group(40L)), false).report().ready()).isFalse();
    }
    @Test void automaticAdminRecheckKeepsOriginalAndBlocksDemotionDespiteReplacement() {
        var group = group(40L); group.setBindingsJson("original");
        when(content.decodeBindings("original")).thenReturn(Map.of("A", 1L, "P1", 2L));
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(List.of(
                adminFact(40L, 1L, 99L), adminFact(40L, 7L, 99L), fact(40L, 2L, 30L, 1, true, true)));
        assertThat(service.inspect(30L, automaticSteps(), List.of(group), true).bindings().get(40L)).containsEntry("A", 1L);
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(List.of(
                new GroupScriptCandidateVO(40L, 1L, 99L, 1, true, true, "WEB", true, false),
                adminFact(40L, 7L, 99L), fact(40L, 2L, 30L, 1, true, true)));
        var result = service.inspect(30L, automaticSteps(), List.of(group), true);
        assertThat(result.report().ready()).isFalse();
        assertThat(result.bindings()).isEmpty();
        assertThat(result.report().groups().get(0).reasons()).anyMatch(reason -> reason.contains("原绑定管理员"));
    }
    @Test void duplicateFixedAdminNamesIdentifyTheAccountAndHowToCorrectTheRoles() {
        var steps = new ArrayList<>(steps(5));
        steps.set(0, new ScriptMarketingStepDTO("ADMIN", 748L, message, "管理员", 0, 0, null, null));
        steps.add(new ScriptMarketingStepDTO("ADMIN", 748L, message, "管理员1", 0, 0, null, null));
        var facts = new ArrayList<GroupScriptCandidateVO>();
        facts.add(fact(40L, 748L, 30L, 1, true, true));
        for (long id = 2; id <= 8; id++) facts.add(fact(40L, id, 30L, 1, true, true));
        when(accounts.listAccounts(any())).thenReturn(PageResult.of(List.of(), 1, 1, 8));
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(facts);
        var result = service.inspect(30L, steps, List.of(group(40L)), false);
        var row = result.report().groups().get(0);
        assertThat(result.report().ready()).isFalse();
        assertThat(result.bindings()).isEmpty();
        assertThat(row.available()).isEqualTo(7);
        assertThat(row.shortage()).isZero();
        assertThat(row.reasons()).singleElement().asString()
                .contains("管理员", "管理员1", "748", "同一账号", "统一角色名称", "不同账号")
                .doesNotContain("协议能力");
        steps.set(steps.size() - 1, steps.get(0));
        assertThat(service.inspect(30L, steps, List.of(group(40L)), false).report().ready()).isTrue();
    }
    @Test void fixedAdminOfflineNamesTheAccountAndDoesNotBlameMessageSupport() {
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(List.of(
                fact(40L, 1L, 99L, 1, true, false), fact(40L, 2L, 30L, 1, true, true)));
        var row = service.inspect(30L, steps(1), List.of(group(40L)), false).report().groups().get(0);
        assertThat(row.ready()).isFalse();
        assertThat(row.reasons()).singleElement().asString().contains("A", "ID：1", "离线或受限", "恢复")
                .doesNotContain("消息", "协议能力");
    }
    @Test void fixedAdminWithoutMembershipRequiresCheckingTheGroupInformation() {
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(List.of(fact(40L, 2L, 30L, 1, true, true)));
        var row = service.inspect(30L, steps(1), List.of(group(40L)), false).report().groups().get(0);
        assertThat(row.ready()).isFalse();
        assertThat(row.reasons()).singleElement().asString().contains("A", "ID：1", "未确认在群", "刷新群资料");
    }
    @Test void fixedAdminWithoutSpeakingPermissionNamesThePermissionToRestore() {
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(List.of(
                fact(40L, 1L, 99L, 1, false, true), fact(40L, 2L, 30L, 1, true, true)));
        var row = service.inspect(30L, steps(1), List.of(group(40L)), false).report().groups().get(0);
        assertThat(row.ready()).isFalse();
        assertThat(row.reasons()).singleElement().asString().contains("A", "ID：1", "发言权限")
                .doesNotContain("离线", "协议能力");
    }
    @Test void unsupportedAdminMessageIdentifiesItsPositionAndAllowedButtonType() {
        var steps = automaticSteps();
        var button = new MarketingTemplateDTO("", 2, null, null, "hello", null,
                List.of(new MessageButton(ButtonType.COPY_CONTENT, "copy", "code")), null, null, false);
        steps.add(new ScriptMarketingStepDTO("ADMIN", null, button, "主持人", 0, 0, null, null));
        when(content.supports(any(), any())).thenCallRealMethod();
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(List.of(
                new GroupScriptCandidateVO(40L, 1L, 99L, 1, true, true, "ANDROID", true, true),
                fact(40L, 2L, 30L, 1, true, true)));
        var result = service.inspect(30L, steps, List.of(group(40L)), false);
        assertThat(result.report().ready()).isFalse();
        assertThat(result.bindings()).isEmpty();
        assertThat(result.report().groups().get(0).reasons()).singleElement().asString()
                .contains("主持人", "第 4 条", "Android", "1 个", "链接跳转", "修改")
                .doesNotContain("无可用", "协议能力");
    }
    @Test void unsupportedPusherMessageIsReportedEvenWhenTheAccountCountIsEnough() {
        var steps = new ArrayList<>(steps(1));
        var button = new MarketingTemplateDTO("", 2, null, null, "hello", null,
                List.of(new MessageButton(ButtonType.QUICK_REPLY, "reply", null)), null, null, false);
        steps.set(1, new ScriptMarketingStepDTO("PROMOTER", null, button, "P1", 0, 0, null, null));
        when(content.supports(any(), any())).thenCallRealMethod();
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(List.of(
                fact(40L, 1L, 99L, 1, true, true),
                new GroupScriptCandidateVO(40L, 2L, 30L, 1, true, true, "ANDROID", true, false)));
        var row = service.inspect(30L, steps, List.of(group(40L)), false).report().groups().get(0);
        assertThat(row.ready()).isFalse();
        assertThat(row.shortage()).isZero();
        assertThat(row.reasons()).singleElement().asString().contains("P1", "第 2 条", "链接跳转")
                .doesNotContain("管理员", "协议能力");
    }
    @Test void overlappingAdminAndPusherCandidatesExplainWhyOneMoreAccountIsNeeded() {
        when(candidates.list(anyList(), anyLong(), anyList())).thenReturn(List.of(adminFact(40L, 1L, 30L)));
        var row = service.inspect(30L, automaticSteps(), List.of(group(40L)), false).report().groups().get(0);
        assertThat(row.ready()).isFalse();
        assertThat(row.shortage()).isZero();
        assertThat(row.reasons()).singleElement().asString().contains("A", "P1", "可用账号重叠", "不同账号")
                .doesNotContain("协议能力");
    }
    List<ScriptMarketingStepDTO> automaticSteps() {
        var result = new ArrayList<>(steps(1));
        result.set(0, new ScriptMarketingStepDTO("ADMIN", null, message, "A", 0, 0, null, null));
        return result;
    }
    GroupScriptCandidateVO adminFact(Long group, Long account, Long pool) {
        return new GroupScriptCandidateVO(group, account, pool, 1, true, true, "WEB", true, true);
    }
    ScriptMarketingGroup group(Long id) {
        var group = new ScriptMarketingGroup(); group.setGroupLinkId(id); group.setGroupName("群" + id);
        group.setGroupJid(id + "@g.us"); return group;
    }
    GroupScriptCandidateVO fact(Long group, Long account, Long pool, int presence, Boolean permission, boolean online) {
        return new GroupScriptCandidateVO(group, account, pool, presence, permission, online, "WEB", true, account == 1L);
    }
}
