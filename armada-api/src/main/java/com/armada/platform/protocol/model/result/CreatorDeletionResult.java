package com.armada.platform.protocol.model.result;
/** 持久化的协议删除结果；ACCEPTED 还必须有匹配 IQ result。 */
public record CreatorDeletionResult(String operationId, String accountHash, String state,
        String iqId, String responseType, String reason) {
    /** 服务端明确接受删除 IQ。 */
    public boolean accepted() {
        return "ACCEPTED".equals(state) && "result".equals(responseType)
                && iqId != null && !iqId.isBlank();
    }
}
