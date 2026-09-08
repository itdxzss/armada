package com.armada.feed.task.mapper;

import com.armada.feed.task.model.entity.FeedTaskAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 动态发布任务账号明细数据访问。 */
@Mapper
public interface FeedTaskAccountMapper {

    int insert(FeedTaskAccount row);

    FeedTaskAccount selectById(@Param("id") Long id);

    List<FeedTaskAccount> selectPage(@Param("taskId") Long taskId,
                                     @Param("accountPhone") String accountPhone,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);

    long countPage(@Param("taskId") Long taskId, @Param("accountPhone") String accountPhone);

    long countOpen(@Param("taskId") Long taskId);

    List<FeedTaskAccount> selectDispatchable(@Param("taskId") Long taskId, @Param("limit") int limit);

    int markSending(@Param("id") Long id,
                    @Param("expectedStatus") String expectedStatus,
                    @Param("commandId") String commandId,
                    @Param("roundNo") Long roundNo,
                    @Param("sendAt") long sendAt);

    int markSuccess(@Param("id") Long id,
                    @Param("messageId") String messageId,
                    @Param("successAt") long successAt);

    int markRetrying(@Param("id") Long id,
                     @Param("failCode") String failCode,
                     @Param("failReason") String failReason,
                     @Param("updatedAt") long updatedAt);

    int markFailed(@Param("id") Long id,
                   @Param("failCode") String failCode,
                   @Param("failReason") String failReason,
                   @Param("failedAt") long failedAt);
}
