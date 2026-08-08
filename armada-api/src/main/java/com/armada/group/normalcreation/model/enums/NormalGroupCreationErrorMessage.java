package com.armada.group.normalcreation.model.enums;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** 新建普群失败原因的运营端中文展示定义。 */
public enum NormalGroupCreationErrorMessage {

    /** 群已创建，但协议端未确认所有成员都已入群。 */
    GROUP_CREATE_PARTIAL(
            "GROUP_CREATE_PARTIAL",
            "群已创建，但部分成员未确认入群，请检查成员状态后处理"),

    /** 当前协议实现缺少执行该动作所需的能力。 */
    PROTOCOL_CAPABILITY_UNAVAILABLE(
            "PROTOCOL_CAPABILITY_UNAVAILABLE",
            "当前协议不支持该建群操作，请联系管理员处理"),

    /** Web 或 Android 协议端拒绝了好友准备动作。 */
    CONTACT_PREPARE_REJECTED(
            "CONTACT_PREPARE_REJECTED",
            "好友准备失败，请检查账号状态后重试"),

    /** Android 新建普群执行器不可用。 */
    SENDER_UNAVAILABLE(
            "SENDER_UNAVAILABLE",
            "Android 建群执行器暂不可用，请联系管理员处理"),

    /** 建群账号不在线。 */
    ACCOUNT_NOT_ONLINE(
            "ACCOUNT_NOT_ONLINE",
            "建群账号当前不在线，请重新上线后重试"),

    /** 目标号码没有注册 WhatsApp。 */
    CONTACT_NOT_REGISTERED(
            "CONTACT_NOT_REGISTERED",
            "目标号码未注册 WhatsApp，请检查成员号码"),

    /** 协议端没有返回可确认的群 ID。 */
    GROUP_CREATE_REJECTED(
            "GROUP_CREATE_REJECTED",
            "WhatsApp 未返回群信息，建群失败，请更换账号或稍后重试"),

    /** 协议端收到不支持的新建普群动作。 */
    INVALID_GROUP_ACTION_PAYLOAD(
            "INVALID_GROUP_ACTION_PAYLOAD",
            "建群指令不受支持，请联系管理员处理"),

    /** Web 分身设备不支持联系人同步密钥。 */
    APP_STATE_DEVICE_UNSUPPORTED(
            "APP_STATE_DEVICE_UNSUPPORTED",
            "当前 Web 协议设备不支持联系人同步，请更换账号后重试"),

    /** Web 联系人同步密钥生成或保存失败。 */
    APP_STATE_KEY_GENERATION_FAILED(
            "APP_STATE_KEY_GENERATION_FAILED",
            "Web 协议设备联系人同步密钥生成失败，请重新同步账号后重试"),

    /** Web 联系人同步密钥无法共享给主设备。 */
    APP_STATE_KEY_SHARE_FAILED(
            "APP_STATE_KEY_SHARE_FAILED",
            "Web 协议设备联系人同步密钥共享失败，请重新同步账号后重试"),

    /** Web 联系人同步密钥激活失败。 */
    APP_STATE_KEY_ACTIVATION_FAILED(
            "APP_STATE_KEY_ACTIVATION_FAILED",
            "Web 协议设备联系人同步密钥激活失败，请重新同步账号后重试");

    private static final String TIMEOUT_CODE = "TIMEOUT";
    private static final String UNCONFIRMED_CODE = "PROTOCOL_RESULT_UNCONFIRMED";
    private static final String PREPARING_CONTACTS = "PREPARING_CONTACTS";
    private static final String CREATING_GROUP = "CREATING_GROUP";
    private static final String APPLYING_SETTINGS = "APPLYING_SETTINGS";
    private static final String LEAVING_GROUP = "LEAVING_GROUP";

    private static final String INTEGRITY_BLOCK_REASON = "blocked-integrity-enforcement";
    private static final String APP_STATE_KEY_MISSING_REASON = "app state key not present";
    private static final String APP_STATE_DEVICE_UNSUPPORTED_REASON =
            "current web credential is not a supported companion device";
    private static final String APP_STATE_KEY_GENERATION_REASON = "failed to generate app state key";
    private static final String APP_STATE_KEY_STORE_REASON = "failed to store generated app state key";
    private static final String APP_STATE_KEY_SHARE_REASON = "failed to share app state key";
    private static final String APP_STATE_KEY_ACTIVATION_REASON = "failed to persist the active app state key";

    private static final String INTEGRITY_BLOCK_MESSAGE =
            "WhatsApp 风控拦截了建群操作，请更换健康账号或稍后重试";
    private static final String APP_STATE_KEY_MISSING_MESSAGE =
            "Web 协议设备缺少联系人同步密钥，请重新同步账号后重试";

    private final String code;
    private final String message;

    NormalGroupCreationErrorMessage(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 将协议层错误转换为新建普群任务详情中的中文业务说明。
     *
     * <p>数据库和日志仍保留协议原始信息；这里只处理 API 出参，未知英文按当前阶段降级为中文提示。</p>
     *
     * @param errorCode 协议结果原因码
     * @param originalMessage 协议原始原因说明
     * @param currentStep 当前建群阶段
     * @return 运营端可直接阅读的中文说明
     */
    public static String resolve(String errorCode, String originalMessage, String currentStep) {
        if ((errorCode == null || errorCode.isBlank())
                && (originalMessage == null || originalMessage.isBlank())) {
            return originalMessage;
        }
        String normalizedMessage = normalize(originalMessage);
        Optional<String> protocolSpecificMessage = protocolSpecificMessage(normalizedMessage);
        if (protocolSpecificMessage.isPresent()) {
            return protocolSpecificMessage.get();
        }
        if (UNCONFIRMED_CODE.equals(errorCode)) {
            return unconfirmedMessage(currentStep);
        }
        if (TIMEOUT_CODE.equals(errorCode)) {
            return timeoutMessage(currentStep);
        }
        for (NormalGroupCreationErrorMessage value : values()) {
            if (value.code.equals(errorCode)) {
                return value.message;
            }
        }
        if (containsChinese(originalMessage)) {
            return originalMessage;
        }
        return fallbackMessage(currentStep);
    }

    private static Optional<String> protocolSpecificMessage(String normalizedMessage) {
        if (normalizedMessage.contains(INTEGRITY_BLOCK_REASON)) {
            return Optional.of(INTEGRITY_BLOCK_MESSAGE);
        }
        if (normalizedMessage.contains(APP_STATE_KEY_MISSING_REASON)) {
            return Optional.of(APP_STATE_KEY_MISSING_MESSAGE);
        }
        if (normalizedMessage.contains(APP_STATE_DEVICE_UNSUPPORTED_REASON)) {
            return Optional.of(APP_STATE_DEVICE_UNSUPPORTED.message);
        }
        if (normalizedMessage.contains(APP_STATE_KEY_GENERATION_REASON)
                || normalizedMessage.contains(APP_STATE_KEY_STORE_REASON)) {
            return Optional.of(APP_STATE_KEY_GENERATION_FAILED.message);
        }
        if (normalizedMessage.contains(APP_STATE_KEY_SHARE_REASON)) {
            return Optional.of(APP_STATE_KEY_SHARE_FAILED.message);
        }
        if (normalizedMessage.contains(APP_STATE_KEY_ACTIVATION_REASON)) {
            return Optional.of(APP_STATE_KEY_ACTIVATION_FAILED.message);
        }
        return Optional.empty();
    }

    private static String unconfirmedMessage(String currentStep) {
        return switch (Objects.toString(currentStep, "")) {
            case PREPARING_CONTACTS -> "好友准备结果未确认，请检查账号状态后重试";
            case CREATING_GROUP -> "建群结果未确认，请先核对群列表，避免重复建群";
            case APPLYING_SETTINGS -> "群权限设置结果未确认，请核对群权限后处理";
            case LEAVING_GROUP -> "建群人退群结果未确认，请核对管理员和成员状态后处理";
            default -> "建群操作结果未确认，请联系管理员核对后处理";
        };
    }

    private static String timeoutMessage(String currentStep) {
        return switch (Objects.toString(currentStep, "")) {
            case PREPARING_CONTACTS -> "好友准备执行超时，请检查账号状态后重试";
            case CREATING_GROUP -> "建群执行超时，结果未确认，请先核对群列表";
            case APPLYING_SETTINGS -> "群权限设置执行超时，结果未确认，请核对群权限";
            case LEAVING_GROUP -> "建群人退群执行超时，结果未确认，请核对成员状态";
            default -> "建群操作执行超时，请联系管理员核对后处理";
        };
    }

    private static String fallbackMessage(String currentStep) {
        return switch (Objects.toString(currentStep, "")) {
            case PREPARING_CONTACTS -> "好友准备未成功，请检查账号状态后重试";
            case CREATING_GROUP -> "建群操作未成功，请更换健康账号或稍后重试";
            case APPLYING_SETTINGS -> "群权限设置未成功，请核对群权限后重试";
            case LEAVING_GROUP -> "建群人退群未成功，请核对管理员和成员状态后重试";
            default -> "建群操作未成功，请联系管理员处理";
        };
    }

    private static String normalize(String message) {
        return message == null ? "" : message.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsChinese(String message) {
        return message != null && message.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }
}
