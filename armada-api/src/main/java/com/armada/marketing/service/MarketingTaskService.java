package com.armada.marketing.service;

import com.armada.marketing.model.dto.CreateMarketingTaskDTO;
import com.armada.marketing.model.dto.MarketingTaskQuery;
import com.armada.marketing.model.vo.MarketingAccountTreeVO;
import com.armada.marketing.model.vo.MarketingTaskDetailVO;
import com.armada.marketing.model.vo.MarketingTaskVO;
import com.armada.shared.response.PageResult;
import java.util.List;

/**
 * 群组营销任务业务入口。
 */
public interface MarketingTaskService {

    /** 分页查询营销任务。 */
    PageResult<MarketingTaskVO> listTasks(MarketingTaskQuery query);

    /** 新建营销任务并生成账号×群组目标。 */
    MarketingTaskVO createTask(CreateMarketingTaskDTO request);

    /** 查询任务详情和目标明细。 */
    MarketingTaskDetailVO getDetail(Long id);

    /** 启动待启动或已停止的营销任务。 */
    MarketingTaskVO startTask(Long id);

    /** 停止发送中的营销任务。 */
    MarketingTaskVO stopTask(Long id);

    /** 批量软删非发送中的营销任务。 */
    int batchDelete(List<Long> ids);

    /** 查询建营销任务用的账号→可营销群树。 */
    MarketingAccountTreeVO accountTree(Long groupId);
}
