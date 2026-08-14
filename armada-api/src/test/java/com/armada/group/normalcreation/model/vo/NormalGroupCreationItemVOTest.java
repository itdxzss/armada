package com.armada.group.normalcreation.model.vo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NormalGroupCreationItemVOTest {

    @Test
    void localizesPartialGroupCreationMessageForDisplay() {
        NormalGroupCreationItemVO item = item(
                "GROUP_CREATE_PARTIAL",
                "group was created but not every requested participant was confirmed",
                "CREATING_GROUP");

        assertThat(item.lastErrorCode()).isEqualTo("GROUP_CREATE_PARTIAL");
        assertThat(item.lastErrorMessage())
                .isEqualTo("群已创建，但部分成员未确认入群，请检查成员状态后处理");
    }

    @Test
    void localizesWhatsappIntegrityBlockForDisplay() {
        NormalGroupCreationItemVO item = item(
                "PROTOCOL_RESULT_UNCONFIRMED",
                "blocked-integrity-enforcement",
                "CREATING_GROUP");

        assertThat(item.lastErrorMessage())
                .isEqualTo("WhatsApp 风控拦截了建群操作，请更换健康账号或稍后重试");
    }

    @Test
    void localizesMissingAppStateKeyForDisplay() {
        NormalGroupCreationItemVO item = item(
                "CONTACT_PREPARE_REJECTED",
                "App state key not present!",
                "PREPARING_CONTACTS");

        assertThat(item.lastErrorMessage())
                .isEqualTo("Web 协议设备缺少联系人同步密钥，请重新同步账号后重试");
    }

    @Test
    void localizesUnconfirmedProtocolResultByCurrentStep() {
        assertThat(item(
                "PROTOCOL_RESULT_UNCONFIRMED", "protocol failure", "PREPARING_CONTACTS")
                .lastErrorMessage()).isEqualTo("好友准备结果未确认，请检查账号状态后重试");
        assertThat(item(
                "PROTOCOL_RESULT_UNCONFIRMED", "protocol failure", "CREATING_GROUP")
                .lastErrorMessage()).isEqualTo("建群结果未确认，请先核对群列表，避免重复建群");
        assertThat(item(
                "PROTOCOL_RESULT_UNCONFIRMED", "protocol failure", "APPLYING_SETTINGS")
                .lastErrorMessage()).isEqualTo("群权限设置结果未确认，请核对群权限后处理");
        assertThat(item(
                "PROTOCOL_RESULT_UNCONFIRMED", "protocol failure", "LEAVING_GROUP")
                .lastErrorMessage()).isEqualTo("建群人退群结果未确认，请核对管理员和成员状态后处理");
    }

    @Test
    void localizesTimeoutByCurrentStep() {
        assertThat(item("TIMEOUT", "timeout", "PREPARING_CONTACTS").lastErrorMessage())
                .isEqualTo("好友准备执行超时，请检查账号状态后重试");
        assertThat(item("TIMEOUT", "timeout", "CREATING_GROUP").lastErrorMessage())
                .isEqualTo("建群执行超时，结果未确认，请先核对群列表");
        assertThat(item("TIMEOUT", "timeout", "APPLYING_SETTINGS").lastErrorMessage())
                .isEqualTo("群权限设置执行超时，结果未确认，请核对群权限");
        assertThat(item("TIMEOUT", "timeout", "LEAVING_GROUP").lastErrorMessage())
                .isEqualTo("建群人退群执行超时，结果未确认，请核对成员状态");
    }

    @Test
    void localizesAppStateBootstrapFailuresForDisplay() {
        assertThat(item(
                "CONTACT_PREPARE_REJECTED",
                "Current Web credential is not a supported companion device",
                "PREPARING_CONTACTS").lastErrorMessage())
                .isEqualTo("当前 Web 协议设备不支持联系人同步，请更换账号后重试");
        assertThat(item(
                "CONTACT_PREPARE_REJECTED",
                "Failed to store generated App State key",
                "PREPARING_CONTACTS").lastErrorMessage())
                .isEqualTo("Web 协议设备联系人同步密钥生成失败，请重新同步账号后重试");
        assertThat(item(
                "CONTACT_PREPARE_REJECTED",
                "Failed to share App State key with the primary device",
                "PREPARING_CONTACTS").lastErrorMessage())
                .isEqualTo("Web 协议设备联系人同步密钥共享失败，请重新同步账号后重试");
        assertThat(item(
                "CONTACT_PREPARE_REJECTED",
                "Failed to persist the active App State key",
                "PREPARING_CONTACTS").lastErrorMessage())
                .isEqualTo("Web 协议设备联系人同步密钥激活失败，请重新同步账号后重试");
    }

    @Test
    void localizesKnownAndroidFailureForDisplay() {
        NormalGroupCreationItemVO item = item(
                "ACCOUNT_NOT_ONLINE",
                "account offline",
                "PREPARING_CONTACTS");

        assertThat(item.lastErrorMessage())
                .isEqualTo("执行账号当前不在线，请检查建群账号和成员账号后重试");
    }

    @Test
    void localizesContactRateLimitStopsForDisplay() {
        NormalGroupCreationContactFailureVO failure =
                new NormalGroupCreationContactFailureVO(
                        21L, 1, 301L, "WEB",
                        "FAILED", "CONTACT_RATE_LIMITED", "rate-overlimit",
                        "FAILED", "CONTACT_SKIPPED_AFTER_RATE_LIMIT", "skipped");

        assertThat(failure.creatorSaveErrorMessage())
                .isEqualTo("该账号保存联系人触发 WhatsApp 限流，已停止其剩余联系人操作并继续建群");
        assertThat(failure.memberSaveErrorMessage())
                .isEqualTo("该账号后续联系人操作已在首次限流后停止");
    }

    @Test
    void distinguishesCreatorAndMemberOfflineFailuresForDisplay() {
        assertThat(item(
                "ACCOUNT_NOT_ONLINE",
                "建群账号当前不在线，请重新上线后重试",
                "PREPARING_CONTACTS").lastErrorMessage())
                .isEqualTo("建群账号当前不在线，请重新上线后重试");
        assertThat(item(
                "ACCOUNT_NOT_ONLINE",
                "成员账号当前不在线，请将对应成员账号重新上线后重试",
                "PREPARING_CONTACTS").lastErrorMessage())
                .isEqualTo("成员账号当前不在线，请将对应成员账号重新上线后重试");
    }

    @Test
    void doesNotExposeUnrecognizedChineseMessageForLegacyOfflineCode() {
        assertThat(item(
                "ACCOUNT_NOT_ONLINE",
                "账号状态异常但角色未知",
                "PREPARING_CONTACTS").lastErrorMessage())
                .isEqualTo("执行账号当前不在线，请检查建群账号和成员账号后重试");
    }

    @Test
    void hidesUnknownEnglishProtocolMessageBehindChineseStepFallback() {
        NormalGroupCreationItemVO item = item(
                "UNEXPECTED_PROTOCOL_ERROR",
                "unexpected protocol failure",
                "APPLYING_SETTINGS");

        assertThat(item.lastErrorMessage()).isEqualTo("群权限设置未成功，请核对群权限后重试");
    }

    @Test
    void keepsOriginalChineseMessageForUnknownReasonCode() {
        NormalGroupCreationItemVO item = item(
                "UNEXPECTED_PROTOCOL_ERROR",
                "协议账号暂不可用，请稍后重试",
                "CREATING_GROUP");

        assertThat(item.lastErrorMessage()).isEqualTo("协议账号暂不可用，请稍后重试");
    }

    @Test
    void hidesEnglishMessageEvenWhenProtocolOmitsReasonCode() {
        NormalGroupCreationItemVO item = item(
                null,
                "unexpected protocol failure",
                "CREATING_GROUP");

        assertThat(item.lastErrorMessage()).isEqualTo("建群操作未成功，请更换健康账号或稍后重试");
    }

    @Test
    void keepsEmptyErrorFieldsForSuccessfulItem() {
        NormalGroupCreationItemVO item = item(null, null, "DONE");

        assertThat(item.lastErrorMessage()).isNull();
        assertThat(item.creatorWsPhone()).isEqualTo("919000000001");
    }

    private static NormalGroupCreationItemVO item(
            String errorCode,
            String errorMessage,
            String currentStep) {
        return new NormalGroupCreationItemVO(
                11L,
                1,
                "测试群",
                243L,
                "919000000001",
                "WEB",
                "120363000000000@g.us",
                null,
                "CREATED_PARTIAL",
                currentStep,
                "PENDING",
                "SKIPPED",
                errorCode,
                errorMessage,
                1_786_081_280_947L,
                false);
    }
}
