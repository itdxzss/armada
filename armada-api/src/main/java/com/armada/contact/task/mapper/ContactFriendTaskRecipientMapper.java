package com.armada.contact.task.mapper;

import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 通讯录营销任务收件人明细的数据访问。 */
@Mapper
public interface ContactFriendTaskRecipientMapper {

    /**
     * 批量写入收件人。幂等键冲突时忽略，重复展开不会产生重复行。
     *
     * @param rows 收件人行，<b>调用方必须保证非空</b>（空批次 foreach 会生成空 VALUES 语法错）
     * @return 受影响行数
     */
    int insertBatch(@Param("rows") List<ContactFriendTaskRecipient> rows);

    /**
     * 取某账号下待发送的收件人。
     *
     * @param taskAccountId 任务账号行 ID
     * @param limit 最多取多少条
     * @return 待发送收件人，按 id 升序
     */
    List<ContactFriendTaskRecipient> selectPendingByAccount(
            @Param("taskAccountId") Long taskAccountId, @Param("limit") int limit);

    /**
     * 取本任务下仍有待发送收件人的账号行 ID。
     *
     * @param taskId 任务 ID
     * @param limit 最多取多少个账号，由任务 concurrency 约束
     * @return 任务账号行 ID
     */
    List<Long> selectAccountIdsWithPending(@Param("taskId") Long taskId, @Param("limit") int limit);

    /**
     * 把一条收件人从 PENDING 抢成 SENDING，写入本轮轮次与命令 ID 并自增尝试次数。
     *
     * @param id 收件人 ID
     * @param roundNo 本轮轮次号
     * @param commandId 协议命令 ID
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 1 表示抢占成功，0 表示已被其他轮次抢走
     */
    int claimForSend(@Param("id") Long id,
                     @Param("roundNo") Long roundNo,
                     @Param("commandId") String commandId,
                     @Param("updatedAt") long updatedAt);

    /**
     * 回写成功结果。仅 SENDING 行会被更新，重复回执返回 0。
     *
     * @param id 收件人 ID
     * @param protocolMessageId 协议返回的消息 ID
     * @param resultAt 结果时间（epoch 毫秒）
     * @return 实际更新行数
     */
    int markSuccess(@Param("id") Long id,
                    @Param("protocolMessageId") String protocolMessageId,
                    @Param("resultAt") long resultAt);

    /**
     * 回写终态失败。仅 SENDING 行会被更新，重复回执返回 0。
     *
     * @param id 收件人 ID
     * @param errorCode 失败码
     * @param errorDesc 失败描述
     * @param resultAt 结果时间（epoch 毫秒）
     * @return 实际更新行数
     */
    int markFailed(@Param("id") Long id,
                   @Param("errorCode") String errorCode,
                   @Param("errorDesc") String errorDesc,
                   @Param("resultAt") long resultAt);

    /**
     * 回写可重试失败，置回 PENDING 等下一轮。仅 SENDING 行会被更新。
     *
     * @param id 收件人 ID
     * @param errorCode 失败码
     * @param errorDesc 失败描述
     * @param resultAt 结果时间（epoch 毫秒）
     * @return 实际更新行数
     */
    int markRetry(@Param("id") Long id,
                  @Param("errorCode") String errorCode,
                  @Param("errorDesc") String errorDesc,
                  @Param("resultAt") long resultAt);

    /**
     * 统计任务下未落终态（PENDING 或 SENDING）的收件人数。
     *
     * @param taskId 任务 ID
     * @return 未完成条数
     */
    long countUnfinished(@Param("taskId") Long taskId);

    /**
     * 统计任务下已投递未回执（SENDING）的收件人数，用作积压闸门。
     *
     * @param taskId 任务 ID
     * @return 在途条数
     */
    long countInFlight(@Param("taskId") Long taskId);
}
