package com.armada.group.model.vo;

/** 群内账号资格事实；未确认的成员或发言权限不视为可发送。 */
public record GroupScriptCandidateVO(Long groupLinkId, Long accountId, Long accountGroupId,
        Integer presenceStatus, Boolean messageSendAllowed, Boolean online,
        String protocolId, Boolean groupAvailable) { }
