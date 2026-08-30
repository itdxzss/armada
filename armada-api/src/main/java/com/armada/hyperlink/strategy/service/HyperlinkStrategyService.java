package com.armada.hyperlink.strategy.service;

import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyCreateDTO;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyQuery;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyUpdateDTO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyAccountContextVO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyDetailVO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyListItemVO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyOptionVO;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.vo.HyperlinkAccountMatchCountVO;
import com.armada.shared.response.PageResult;
import java.util.List;

/** 超链策略页面和任务策略选择器业务服务。 */
public interface HyperlinkStrategyService {

    /** 按名称、任务模式和启用状态分页查询当前租户策略。 */
    PageResult<HyperlinkStrategyListItemVO> list(HyperlinkStrategyQuery query);

    /** 查询当前租户有效策略完整详情。 */
    HyperlinkStrategyDetailVO detail(Long id);

    /** 查询启用策略候选，任务只复制返回字段，不保存运行时强引用。 */
    List<HyperlinkStrategyOptionVO> options(String keyword, Integer limit);

    /** 创建策略并记录可信创建人。 */
    HyperlinkStrategyDetailVO create(HyperlinkStrategyCreateDTO request, long createdBy);

    /** 按期望版本完整更新策略。 */
    HyperlinkStrategyDetailVO update(Long id, HyperlinkStrategyUpdateDTO request);

    /** 软删除策略，不检查已复制到任务的历史值。 */
    void delete(Long id);

    /** 返回不依赖钱包的账号筛选下拉上下文。 */
    HyperlinkStrategyAccountContextVO accountContext();

    /** 按任务实际选号口径试算账号数。 */
    HyperlinkAccountMatchCountVO accountMatchCount(HyperlinkAccountFilterDTO filter);
}
