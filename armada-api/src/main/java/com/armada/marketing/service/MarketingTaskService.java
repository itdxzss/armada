package com.armada.marketing.service;

import com.armada.marketing.model.dto.CreateMarketingTaskDTO;
import com.armada.marketing.model.dto.MarketingTaskQuery;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.vo.MarketingAccountTreeVO;
import com.armada.marketing.model.vo.MarketingTaskDetailVO;
import com.armada.marketing.model.vo.MarketingTaskVO;
import com.armada.marketing.model.vo.MarketingTemplateVO;
import com.armada.marketing.model.vo.MarketingTreeAccountVO;
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

    /** 启动未启动任务；未到计划开始时间时保持未启动并等待自动调度。 */
    MarketingTaskVO startTask(Long id);

    /** 暂停执行中的任务，账号继续由当前任务持有。 */
    MarketingTaskVO pauseTask(Long id);

    /** 恢复已暂停任务。 */
    MarketingTaskVO resumeTask(Long id);

    /** 手动关闭非终态任务并释放其全部账号。 */
    MarketingTaskVO closeTask(Long id);

    /** 批量软删非发送中的营销任务。 */
    int batchDelete(List<Long> ids);

    /** 查询建营销任务用的账号树首屏;只返回账号,不实时查群。 */
    MarketingAccountTreeVO accountTree(Long groupId);

    /** 懒加载单个账号的实时可营销群。 */
    MarketingTreeAccountVO accountGroups(Long accountId);

    /** 通过任务更新其引用的共享营销模板。 */
    MarketingTemplateVO updateMarketingTemplate(Long id, MarketingTemplateDTO request);
}
