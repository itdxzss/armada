package com.armada.account.model.dto;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
/** 已提交短事务的认领快照；HTTP 在事务外使用，attemptNo 拒绝晚到结果。 */
public record AccountTierCheckClaim(AccountTierCheckWork work, int attemptNo,
        ProtocolAccountRef executor, String executorIdentity, String phone) {}
