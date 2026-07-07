package com.armada.marketing.mapper;

import com.armada.marketing.model.dto.GroupCreationMarketingTaskQuery;
import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.armada.marketing.model.entity.GroupCreationMarketingTask;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GroupCreationMarketingTaskMapper {

    int insertTask(GroupCreationMarketingTask task);

    int insertItems(@Param("items") List<GroupCreationMarketingItem> items);

    GroupCreationMarketingTask selectTaskById(@Param("id") Long id);

    GroupCreationMarketingItem selectItemById(@Param("id") Long id);

    List<GroupCreationMarketingItem> selectItemsByTaskId(@Param("taskId") Long taskId);

    long countPage(@Param("q") GroupCreationMarketingTaskQuery query);

    List<GroupCreationMarketingTask> selectPage(@Param("q") GroupCreationMarketingTaskQuery query);

    List<GroupCreationMarketingAccountCandidate> selectAccountCandidatesByGroupId(@Param("accountGroupId") Long accountGroupId);

    GroupCreationMarketingAccountCandidate selectFirstAvailableAccountCandidateByGroupId(@Param("accountGroupId") Long accountGroupId);

    GroupCreationMarketingAccountCandidate selectFirstAvailableAccountCandidateByGroupIdExcluding(
            @Param("accountGroupId") Long accountGroupId,
            @Param("excludedAccountIds") List<Long> excludedAccountIds);

    int countStoppableItems(@Param("taskId") Long taskId);

    int stopStoppableItems(@Param("taskId") Long taskId,
                           @Param("reasonCode") String reasonCode,
                           @Param("reasonMessage") String reasonMessage,
                           @Param("finishedAt") long finishedAt);

    int stopTask(@Param("id") Long id,
                 @Param("status") int status,
                 @Param("abandonedDelta") int abandonedDelta,
                 @Param("finishedAt") long finishedAt);

    @InterceptorIgnore(tenantLine = "true")
    List<GroupCreationMarketingItem> selectDueItems(@Param("limit") int limit, @Param("now") long now);

    int claimItem(@Param("id") Long id,
                  @Param("fromStatus") int fromStatus,
                  @Param("toStatus") int toStatus,
                  @Param("now") long now);

    GroupCreationMarketingAccountCandidate selectAccountCandidateByAccountId(@Param("accountId") Long accountId);

    int updateItemAccountIfCreating(@Param("id") Long id,
                                    @Param("accountId") Long accountId,
                                    @Param("accountPhone") String accountPhone,
                                    @Param("protocolAccountId") String protocolAccountId,
                                    @Param("updatedAt") long updatedAt);

    int updateItemAccountForClaimRetry(@Param("id") Long id,
                                       @Param("accountId") Long accountId,
                                       @Param("accountPhone") String accountPhone,
                                       @Param("protocolAccountId") String protocolAccountId,
                                       @Param("retryHistoryJson") String retryHistoryJson,
                                       @Param("updatedAt") long updatedAt);

    int resetItemForAccountRetry(@Param("id") Long id,
                                 @Param("accountId") Long accountId,
                                 @Param("accountPhone") String accountPhone,
                                 @Param("protocolAccountId") String protocolAccountId,
                                 @Param("fromStatus") int fromStatus,
                                 @Param("expectedCommandId") String expectedCommandId,
                                 @Param("pendingStatus") int pendingStatus,
                                 @Param("nextRunAt") long nextRunAt,
                                 @Param("retryHistoryJson") String retryHistoryJson,
                                 @Param("updatedAt") long updatedAt);

    int updateTaskMarketingTaskIdIfAbsent(@Param("taskId") Long taskId,
                                          @Param("marketingTaskId") Long marketingTaskId,
                                          @Param("updatedAt") long updatedAt);

    int markItemMarketingSending(@Param("id") Long id,
                                 @Param("groupJid") String groupJid,
                                 @Param("groupLinkId") Long groupLinkId,
                                 @Param("marketingTaskId") Long marketingTaskId,
                                 @Param("marketingTargetId") Long marketingTargetId,
                                 @Param("marketingAttemptId") Long marketingAttemptId,
                                 @Param("commandId") String commandId,
                                 @Param("participantResultJson") String participantResultJson,
                                 @Param("updatedAt") long updatedAt);

    int markItemAbandoned(@Param("id") Long id,
                          @Param("reasonCode") String reasonCode,
                          @Param("reasonMessage") String reasonMessage,
                          @Param("finishedAt") long finishedAt);

    int markItemNoAvailableAccount(@Param("id") Long id,
                                   @Param("reasonCode") String reasonCode,
                                   @Param("reasonMessage") String reasonMessage,
                                   @Param("fromStatus") int fromStatus,
                                   @Param("expectedCommandId") String expectedCommandId,
                                   @Param("retryHistoryJson") String retryHistoryJson,
                                   @Param("finishedAt") long finishedAt);

    int markItemFailed(@Param("id") Long id,
                       @Param("reasonCode") String reasonCode,
                       @Param("reasonMessage") String reasonMessage,
                       @Param("participantResultJson") String participantResultJson,
                       @Param("finishedAt") long finishedAt);

    int markItemSuccessByMarketingAttemptId(@Param("attemptId") Long attemptId,
                                            @Param("finishedAt") long finishedAt);

    int markItemFailedByMarketingAttemptId(@Param("attemptId") Long attemptId,
                                           @Param("reasonCode") String reasonCode,
                                           @Param("reasonMessage") String reasonMessage,
                                           @Param("finishedAt") long finishedAt);

    int markItemSuccessByCommandId(@Param("itemId") Long itemId,
                                   @Param("commandId") String commandId,
                                   @Param("groupJid") String groupJid,
                                   @Param("messageId") String messageId,
                                   @Param("finishedAt") long finishedAt);

    int markItemFailedByCommandId(@Param("itemId") Long itemId,
                                  @Param("commandId") String commandId,
                                  @Param("reasonCode") String reasonCode,
                                  @Param("reasonMessage") String reasonMessage,
                                  @Param("finishedAt") long finishedAt);
}
