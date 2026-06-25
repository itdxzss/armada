package com.armada.group.model.dto;

import com.armada.shared.paging.PageQuery;

/**
 * 群链接导入明细列表查询参数(可变 class extends PageQuery,供 @ModelAttribute 绑定)。
 */
public class GroupLinkImportDetailQuery extends PageQuery {

    /** 所属WS链接分组 ID(按分组查)。 */
    private Long labelId;

    /** 所属批次 ID(按批次查)。 */
    private Long batchId;

    /** 导入结果过滤(1=成功 2=已存在 3=批内重复 4=格式错误)。 */
    private Integer result;

    public Long getLabelId() {
        return labelId;
    }

    public void setLabelId(Long labelId) {
        this.labelId = labelId;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Integer getResult() {
        return result;
    }

    public void setResult(Integer result) {
        this.result = result;
    }
}
