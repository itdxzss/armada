package com.armada.account.model.enums;

/** 账号操作能力限制状态，数据库 {@code account_state.mute_status} 使用该码值。 */
public enum AccountOperationRestrictionStatus {

    /** 仅限制商业营销消息发送。 */
    MESSAGE_SENDING_RESTRICTED(1),

    /** 进群与拉人受限。 */
    PULLING_RESTRICTED(2),

    /** 消息发送、进群与拉人同时受限，各能力保留独立截止时间。 */
    MESSAGE_SENDING_AND_PULLING_RESTRICTED(3);

    private final int code;

    AccountOperationRestrictionStatus(int code) {
        this.code = code;
    }

    /** @return 数据库存储码 */
    public int code() {
        return code;
    }

    /** @return 当前状态是否限制营销消息发送 */
    public static boolean restrictsMessageSending(Integer status) {
        return status != null
                && (status == MESSAGE_SENDING_RESTRICTED.code
                || status == MESSAGE_SENDING_AND_PULLING_RESTRICTED.code);
    }

    /** @return 当前状态是否限制进群与拉人 */
    public static boolean restrictsPulling(Integer status) {
        return status != null
                && (status == PULLING_RESTRICTED.code
                || status == MESSAGE_SENDING_AND_PULLING_RESTRICTED.code);
    }

}
