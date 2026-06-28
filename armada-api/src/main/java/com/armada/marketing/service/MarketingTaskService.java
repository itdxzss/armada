package com.armada.marketing.service;

import com.armada.marketing.model.dto.CreateMarketingTaskDTO;
import com.armada.marketing.model.dto.MarketingTaskQuery;
import com.armada.marketing.model.vo.MarketingTaskDetailVO;
import com.armada.marketing.model.vo.MarketingTaskVO;
import com.armada.shared.response.PageResult;

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
}
