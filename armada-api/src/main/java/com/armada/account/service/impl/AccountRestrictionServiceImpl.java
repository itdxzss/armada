package com.armada.account.service.impl;

import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.service.AccountRestrictionService;
import com.armada.platform.protocol.model.command.ProtocolOfflineCommandRequest;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 账号受限状态服务实现。
 *
 * <p>当前用于建群营销场景:当协议层返回账号 reachout/group create restricted 时,
 * 将账号登录态置为离线、账号状态置为受限,记录限制原因,并通过 outbox 提交协议下线命令。</p>
 */
@Service
public class AccountRestrictionServiceImpl implements AccountRestrictionService {

    /** 写入 account_state.state_source 的建群受限来源。 */
    private static final String STATE_SOURCE_GROUP_CREATE_RESTRICTED = "GROUP_CREATE_RESTRICTED";

    /** 协议下线命令 source,用于排查建群受限触发的离线。 */
    private static final String OFFLINE_SOURCE_GROUP_CREATE_RESTRICTED = "group_create_restricted";

    /** 协议层未给出具体原因时写入的默认限制原因。 */
    private static final String DEFAULT_REASON = "account_reachout_restricted";

    /** account_state.block_reason 字段最大写入长度。 */
    private static final int BLOCK_REASON_MAX_LENGTH = 255;

    /** 账号状态 Mapper。 */
    private final AccountStateMapper stateMapper;

    /** 协议命令 outbox 服务,用于提交下线命令。 */
    private final ProtocolCommandOutboxService outboxService;

    /**
     * 注入账号受限状态依赖。
     *
     * @param stateMapper   账号状态 Mapper
     * @param outboxService 协议命令 outbox 服务
     */
    public AccountRestrictionServiceImpl(AccountStateMapper stateMapper,
                                         ProtocolCommandOutboxService outboxService) {
        this.stateMapper = stateMapper;
        this.outboxService = outboxService;
    }

    /**
     * 标记建群受限账号并提交下线命令。
     *
     * <p>本地状态更新和 outbox 提交由调用方事务边界控制。protocolAccountId 为空时不会提交协议下线命令,
     * 避免生成无法投递的协议命令。</p>
     *
     * @param accountId         账号 ID,不能为空
     * @param protocolAccountId 协议层账号 ID
     * @param reason            协议层限制原因
     * @param occurredAt        受限事件发生时间(epoch 毫秒)
     */
    @Override
    public void markGroupCreateRestricted(Long accountId, String protocolAccountId, String reason, long occurredAt) {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId must not be null");
        }
        long updatedAt = System.currentTimeMillis();
        String blockReason = clamp(StringUtils.hasText(reason) ? reason : DEFAULT_REASON, BLOCK_REASON_MAX_LENGTH);
        AccountState row = new AccountState();
        row.setAccountId(accountId);
        row.setLoginState(AccountLoginStateCode.OFFLINE);
        row.setAccountState(AccountStateCode.RESTRICTED);
        row.setStateSource(STATE_SOURCE_GROUP_CREATE_RESTRICTED);
        row.setBlockReason(blockReason);
        row.setLastStateSyncTime(occurredAt);
        row.setUpdatedAt(updatedAt);

        stateMapper.updateLifecycleState(row);
        stateMapper.updateBlockReason(row);
        if (StringUtils.hasText(protocolAccountId)) {
            outboxService.enqueueOfflineCommands(List.of(new ProtocolOfflineCommandRequest(
                    accountId,
                    protocolAccountId,
                    OFFLINE_SOURCE_GROUP_CREATE_RESTRICTED)));
        }
    }

    private static String clamp(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
