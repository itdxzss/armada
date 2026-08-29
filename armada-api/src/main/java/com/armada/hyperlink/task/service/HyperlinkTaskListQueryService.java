package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.model.dto.HyperlinkTaskListQuery;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskListExportFile;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskListItemVO;
import com.armada.shared.response.PageResult;

/** H1 超链任务只读列表与同步导出服务。 */
public interface HyperlinkTaskListQueryService {

    /** 按冻结筛选条件分页查询任务列表。 */
    PageResult<HyperlinkTaskListItemVO> list(HyperlinkTaskListQuery query);

    /** 按冻结筛选条件同步导出任务列表。 */
    HyperlinkTaskListExportFile export(HyperlinkTaskListQuery query);
}
