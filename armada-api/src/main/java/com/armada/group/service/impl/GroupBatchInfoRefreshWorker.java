package com.armada.group.service.impl;

import com.armada.group.model.dto.GroupMetadataSnapshotRequest;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupMetadataSnapshotService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 批量获取最新群信息的单项执行器。
 *
 * <p>实时直调协议读取群 metadata 并回填快照,不排进 group_metadata_sync_task 等结果:
 * 队列只能给出"某次同步成功了"的间接信号,失败原因隔了一层,而且退避重试与 PRD P-05
 * "失败项不重试"的口径相反,前端只会看到进度长时间不动。</p>
 *
 * <p>协议读取、字段级空值保护与成员快照落库仍复用 {@link GroupMetadataSnapshotService},
 * 不存在第二套快照解析逻辑;并发下同一账号由账号闸门串行。</p>
 */
@Component
public class GroupBatchInfoRefreshWorker {

    private static final Logger log = LoggerFactory.getLogger(GroupBatchInfoRefreshWorker.class);

    private static final String SUCCESS_MESSAGE = "群信息已刷新";
    private static final String NO_ACCOUNT_CODE = "NO_AVAILABLE_ACCOUNT";
    private static final String NO_ACCOUNT_MESSAGE = "系统内没有在线且仍在该群内的账号";
    private static final String NO_JID_CODE = "GROUP_JID_UNKNOWN";
    private static final String NO_JID_MESSAGE = "群组标识未知，无法读取群信息";
    private static final String PROTOCOL_FAILURE_CODE = "METADATA_FETCH_FAILED";
    private static final String PROTOCOL_FAILURE_PREFIX = "读取群信息失败：";
    private static final String DB_FAILURE_CODE = "DB_WRITE_FAILED";
    private static final String DB_FAILURE_MESSAGE = "群信息写入失败（数据库繁忙），请稍后重试";

    private final GroupBatchRefreshSupport support;
    private final GroupMetadataSnapshotService snapshotService;

    /** 创建批量获取最新群信息执行器。 */
    public GroupBatchInfoRefreshWorker(
            GroupBatchRefreshSupport support, GroupMetadataSnapshotService snapshotService) {
        this.support = support;
        this.snapshotService = snapshotService;
    }

    /**
     * 执行并结算一条获取最新群信息明细。
     *
     * <p>任何失败都只影响本项,旧快照原样保留(BR-05、BR-10)。</p>
     *
     * @param item 待执行明细
     * @param now 结算时间(epoch 毫秒)
     */
    public void execute(GroupBatchTaskItem item, long now) {
        // 批量项不重试(PRD P-05),固定取第 0 号候选账号。
        Optional<GroupExecutionAccount> selected = support.selector().find(item.getGroupLinkId(), 0);
        if (selected.isEmpty()) {
            settle(failed(item, null, null, NO_ACCOUNT_CODE, NO_ACCOUNT_MESSAGE, now));
            return;
        }
        GroupExecutionAccount account = selected.get();
        String groupJid = support.groupJid(item.getGroupLinkId());
        if (groupJid == null) {
            settle(failed(
                    item, account.accountId(), null, NO_JID_CODE, NO_JID_MESSAGE, now));
            return;
        }
        try {
            support.throttle().run(
                    account.accountId(),
                    () -> snapshotService.refresh(request(item, groupJid), account));
            settle(succeeded(item, account.accountId(), groupJid, now));
        } catch (RuntimeException exception) {
            settle(failure(item, account, groupJid, exception, now));
        }
    }

    /**
     * 按失败来源分类结算。
     *
     * <p>数据库异常与协议失败必须分开:错误码不同、文案不同,日志级别也不同(数据库异常按
     * error 报出来,便于告警);两者都只记异常类型与群标识,群成员手机号等明细不进日志。</p>
     */
    private static GroupBatchTaskItem failure(
            GroupBatchTaskItem item,
            GroupExecutionAccount account,
            String groupJid,
            RuntimeException exception,
            long now) {
        if (GroupBatchTaskOutcomes.databaseFailure(exception)) {
            log.error("批量获取最新群信息写库失败 groupLinkId={} groupJid={} errorType={}",
                    item.getGroupLinkId(), groupJid, exception.getClass().getSimpleName());
            return failed(item, account.accountId(), groupJid, DB_FAILURE_CODE,
                    DB_FAILURE_MESSAGE + "（" + exception.getClass().getSimpleName() + "）", now);
        }
        log.warn("批量获取最新群信息失败 groupLinkId={} groupJid={} errorType={}",
                item.getGroupLinkId(), groupJid, exception.getClass().getSimpleName());
        return failed(item, account.accountId(), groupJid, PROTOCOL_FAILURE_CODE,
                GroupBatchTaskOutcomes.reason(exception, PROTOCOL_FAILURE_PREFIX), now);
    }

    /**
     * 构造快照读取请求。
     *
     * <p>{@code inviteRequired = false}:本按钮只负责群信息,取不到邀请码不该把整项判失败,
     * 那是"刷新群链接"按钮的职责。</p>
     */
    private static GroupMetadataSnapshotRequest request(GroupBatchTaskItem item, String groupJid) {
        return new GroupMetadataSnapshotRequest(item.getGroupLinkId(), groupJid, 0, false);
    }

    private void settle(GroupBatchTaskItem outcome) {
        support.settlement().settle(outcome);
    }

    private static GroupBatchTaskItem succeeded(
            GroupBatchTaskItem item, Long accountId, String groupJid, long now) {
        GroupBatchTaskItem outcome =
                GroupBatchTaskOutcomes.outcome(item, accountId, groupJid, now);
        outcome.setStatus(GroupBatchTaskItemStatus.SUCCESS.code());
        outcome.setDescription(SUCCESS_MESSAGE);
        return outcome;
    }

    private static GroupBatchTaskItem failed(
            GroupBatchTaskItem item,
            Long accountId,
            String groupJid,
            String errorCode,
            String description,
            long now) {
        GroupBatchTaskItem outcome =
                GroupBatchTaskOutcomes.outcome(item, accountId, groupJid, now);
        outcome.setStatus(GroupBatchTaskItemStatus.FAILED.code());
        outcome.setErrorCode(errorCode);
        outcome.setDescription(description);
        return outcome;
    }
}
