package com.armada.group.service;

import com.armada.group.model.dto.GroupLinkImportDTO;
import com.armada.group.model.dto.GroupLinkImportDetailQuery;
import com.armada.group.model.vo.GroupLinkImportDetailVO;
import com.armada.group.model.vo.GroupLinkImportResultVO;
import com.armada.shared.response.PageResult;
import java.util.List;

/**
 * 群链接导入业务接口。
 */
public interface GroupLinkImportService {

    /**
     * 同步导入群链接(upsert-by-url):新链接插入;同 url 已活跃存在则记「已存在」不导入(原链接不动,换组走迁移);同 url 为软删则复活到目标分组。
     * 逐行归一化、批内去重,最终写 batch + detail 两表并回写统计。
     *
     * @param dto 导入请求(含 labelId/batchName/lines)
     * @return 导入汇总结果 VO
     */
    GroupLinkImportResultVO importLinks(GroupLinkImportDTO dto);

    /**
     * 导入明细分页列表(JOIN batch 取 sourceFileName/labelId)。
     *
     * @param query 查询条件(labelId/batchId/result/failReason/page/pageSize)
     * @return 分页明细 VO
     */
    PageResult<GroupLinkImportDetailVO> listDetails(GroupLinkImportDetailQuery query);

    /**
     * 导出失败明细(result=2),返回 CSV 数据行列表(不含表头),重复/格式错误由 failReason 区分。
     * 每行格式:String[]{ 行号, 群名称, 群链接, 失败原因, 导入时间(Asia/Shanghai 可读串) }。
     *
     * @param labelId 分组 ID(可选,与 batchId 至少提供一个)
     * @param batchId 批次 ID(可选)
     * @return CSV 数据行列表(每行为 String 数组)
     */
    List<String[]> exportFailed(Long labelId, Long batchId);
}
