package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.model.enums.GroupLinkPrecheckStatus;
import com.armada.group.model.vo.GroupLinkPrecheckItemVO;
import com.armada.group.model.vo.GroupLinkPrecheckResultVO;
import com.armada.group.service.GroupInvitePageFetcher;
import com.armada.group.service.GroupInvitePageMetadata;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 群链接导入前预检测服务单测。
 */
@ExtendWith(MockitoExtension.class)
class GroupLinkPrecheckServiceImplTest {

    private static final String RAW_LINK = "1. https://chat.whatsapp.com/AbcDef1234567890123456?utm=abc";
    private static final String NORMALIZED_LINK = "chat.whatsapp.com/AbcDef1234567890123456";

    @Mock
    private GroupInvitePageFetcher invitePageFetcher;

    @Test
    void precheck_marksAvailableWhenInvitePageHasProfile() {
        GroupLinkPrecheckServiceImpl service = new GroupLinkPrecheckServiceImpl(invitePageFetcher);
        when(invitePageFetcher.fetch(NORMALIZED_LINK))
                .thenReturn(new GroupInvitePageMetadata(
                        "AbcDef1234567890123456",
                        "2017+44",
                        "https://pps.whatsapp.net/v/t61.24694-24/avatar.jpg"));

        GroupLinkPrecheckResultVO result = service.precheck(List.of(RAW_LINK));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.available()).isEqualTo(1);
        assertThat(result.unavailable()).isZero();
        GroupLinkPrecheckItemVO item = result.items().get(0);
        assertThat(item.lineNo()).isEqualTo(1);
        assertThat(item.rawUrl()).isEqualTo(RAW_LINK);
        assertThat(item.normalizedUrl()).isEqualTo(NORMALIZED_LINK);
        assertThat(item.inviteCode()).isEqualTo("AbcDef1234567890123456");
        assertThat(item.groupName()).isEqualTo("2017+44");
        assertThat(item.avatarUrl()).isEqualTo("https://pps.whatsapp.net/v/t61.24694-24/avatar.jpg");
        assertThat(item.status()).isEqualTo(GroupLinkPrecheckStatus.AVAILABLE.code());
        assertThat(item.statusLabel()).isEqualTo("可用");
        assertThat(item.failReason()).isNull();
    }

    @Test
    void precheck_marksFormatErrorUnavailableAndSkipsFetcher() {
        GroupLinkPrecheckServiceImpl service = new GroupLinkPrecheckServiceImpl(invitePageFetcher);

        GroupLinkPrecheckResultVO result = service.precheck(List.of("not a link"));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.available()).isZero();
        assertThat(result.unavailable()).isEqualTo(1);
        GroupLinkPrecheckItemVO item = result.items().get(0);
        assertThat(item.status()).isEqualTo(GroupLinkPrecheckStatus.UNAVAILABLE.code());
        assertThat(item.statusLabel()).isEqualTo("不可用");
        assertThat(item.failReason()).isEqualTo("缺少群邀请链接");
        assertThat(item.normalizedUrl()).isNull();
        verify(invitePageFetcher, never()).fetch(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void precheck_marksNoProfileAsUnavailable() {
        GroupLinkPrecheckServiceImpl service = new GroupLinkPrecheckServiceImpl(invitePageFetcher);
        when(invitePageFetcher.fetch(NORMALIZED_LINK))
                .thenReturn(new GroupInvitePageMetadata("AbcDef1234567890123456", null, null));

        GroupLinkPrecheckResultVO result = service.precheck(List.of(RAW_LINK));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.available()).isZero();
        assertThat(result.unavailable()).isEqualTo(1);
        GroupLinkPrecheckItemVO item = result.items().get(0);
        assertThat(item.normalizedUrl()).isEqualTo(NORMALIZED_LINK);
        assertThat(item.inviteCode()).isEqualTo("AbcDef1234567890123456");
        assertThat(item.status()).isEqualTo(GroupLinkPrecheckStatus.UNAVAILABLE.code());
        assertThat(item.failReason()).isEqualTo("未识别到群资料");
    }

    @Test
    void precheck_reusesFirstDetectionForDuplicateNormalizedUrl() {
        GroupLinkPrecheckServiceImpl service = new GroupLinkPrecheckServiceImpl(invitePageFetcher);
        when(invitePageFetcher.fetch(NORMALIZED_LINK))
                .thenReturn(new GroupInvitePageMetadata("AbcDef1234567890123456", "2017+44", null));

        GroupLinkPrecheckResultVO result = service.precheck(List.of(
                RAW_LINK,
                "https://chat.whatsapp.com/AbcDef1234567890123456"));

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.available()).isEqualTo(2);
        assertThat(result.items())
                .extracting(GroupLinkPrecheckItemVO::normalizedUrl)
                .containsExactly(NORMALIZED_LINK, NORMALIZED_LINK);
        verify(invitePageFetcher).fetch(NORMALIZED_LINK);
    }
}
