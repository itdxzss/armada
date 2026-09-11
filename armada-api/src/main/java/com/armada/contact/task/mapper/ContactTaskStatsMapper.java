package com.armada.contact.task.mapper;

import com.armada.contact.task.model.dto.ContactTaskAccountStatsQuery;
import com.armada.contact.task.model.dto.ContactTaskRecipientQuery;
import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import com.armada.contact.task.model.vo.ContactTaskAccountSummaryVO;
import com.armada.contact.task.model.vo.ContactTaskMetricsVO;
import com.armada.contact.task.model.vo.ContactTaskReasonVO;
import com.armada.contact.task.model.vo.ContactTaskRecipientVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 通讯录任务事实聚合及服务端明细筛选，查询不改变发送生命周期。 */
@Mapper
public interface ContactTaskStatsMapper {
    /** 限定当前页任务 ID 批量聚合消息，不扫描其他任务。 */
    List<ContactTaskMetricsVO> selectTaskMetrics(@Param("taskIds") List<Long> taskIds);
    /** 限定任务及当前页账号，批量读取各账号消息指标。 */
    List<ContactTaskMetricsVO> selectAccountMetrics(@Param("taskId") Long taskId,
            @Param("accountIds") List<Long> accountIds);
    /** 读取名单准备和任务执行异常账号数。 */
    List<ContactTaskAccountSummaryVO> selectAccountSummaries(@Param("taskIds") List<Long> taskIds);
    /** 在整个任务账号集合上排序，再执行分页。 */
    List<ContactFriendTaskAccount> selectAccountPage(@Param("query") ContactTaskAccountStatsQuery query);
    /** 异常原因按状态和原因码聚合。 */
    List<ContactTaskReasonVO> selectReasons(@Param("taskId") Long taskId);
    /** 校验账号快照属于当前租户及任务。 */
    long countAccountScope(@Param("taskId") Long taskId, @Param("taskAccountId") Long taskAccountId);
    /** 按与列表相同的过滤条件统计总数。 */
    long countRecipients(@Param("taskId") Long taskId, @Param("query") ContactTaskRecipientQuery query);
    /** 分页读取联系人明细及其发信账号，所有过滤先于分页。 */
    List<ContactTaskRecipientVO> selectRecipients(@Param("taskId") Long taskId,
            @Param("query") ContactTaskRecipientQuery query);
}
