package com.armada.marketing.mapper;

import com.armada.marketing.model.vo.MarketingAccountOccupancyOwnerRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 普通群组营销账号当前占用关系数据访问。
 */
@Mapper
public interface MarketingAccountOccupancyMapper {

    /**
     * 为发送中的普通营销任务抢占当前空闲账号。
     *
     * <p>数据库唯一键保证同租户同账号只有一个当前占用方；冲突账号由 INSERT IGNORE 跳过，
     * 后续通过 owner 查询识别并记录本轮占用明细。</p>
     *
     * @param taskId     普通营销任务 ID
     * @param occupiedAt 抢占时间(epoch毫秒)
     * @return 本次成功新增的账号占用数
     */
    int insertAvailableTaskAccounts(@Param("taskId") Long taskId,
                                    @Param("occupiedAt") long occupiedAt);

    /**
     * 查询指定任务全部目标账号的当前占用方。
     *
     * @param taskId 普通营销任务 ID
     * @return 当前存在有效租约的账号 owner 列表
     */
    List<MarketingAccountOccupancyOwnerRow> selectOwnersByTaskAccounts(@Param("taskId") Long taskId);

    /**
     * 查询账号分组内任意一个当前占用方，用于创建任务时的分组级门禁提示。
     *
     * @param accountGroupId 账号分组 ID
     * @return 第一个占用方；分组内没有被占用账号时返回 null
     */
    MarketingAccountOccupancyOwnerRow selectFirstOwnerByAccountGroupId(
            @Param("accountGroupId") Long accountGroupId);

    /**
     * 释放指定普通营销任务当前持有的全部账号。
     *
     * @param taskId 普通营销任务 ID
     * @return 实际释放账号数
     */
    int releaseByTaskId(@Param("taskId") Long taskId);

    /**
     * 释放引用指定模板的普通营销任务账号租约。
     *
     * <p>模板删除服务会先停止待执行/发送中任务，再按同一批模板 ID 清理其占用。</p>
     *
     * @param templateIds 营销模板 ID 列表
     * @return 实际释放账号数
     */
    int releaseByTemplateIds(@Param("templateIds") List<Long> templateIds);

    /**
     * 删除所属任务已不再发送、已删除、已过结束时间或不存在的残留占用。
     *
     * @param now 当前时间(epoch毫秒)
     * @return 清理行数
     */
    int deleteStale(@Param("now") long now);
}
