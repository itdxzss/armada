package com.armada.group.service;

import com.armada.group.model.dto.GroupLinkImportDTO;
import com.armada.group.model.vo.GroupLinkImportResultVO;

/**
 * 群链接导入业务接口。
 */
public interface GroupLinkImportService {

    /**
     * 同步导入群链接(upsert-by-url):新链接插入,已存在链接收编到目标分组。
     * 逐行归一化、批内去重,最终写 batch + detail 两表并回写统计。
     *
     * @param dto 导入请求(含 labelId/batchName/lines)
     * @return 导入汇总结果 VO
     */
    GroupLinkImportResultVO importLinks(GroupLinkImportDTO dto);
}
