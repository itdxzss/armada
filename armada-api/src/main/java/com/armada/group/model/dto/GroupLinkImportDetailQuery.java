package com.armada.group.model.dto;

import com.armada.group.model.GroupLinkImportResult;
import com.armada.group.model.enums.GroupLinkImportFailReason;
import com.armada.shared.paging.PageQuery;
import com.armada.shared.security.DataScope;

/**
 * 群链接导入明细列表查询参数(可变 class extends PageQuery,供 @ModelAttribute 绑定)。
 */
public class GroupLinkImportDetailQuery extends PageQuery {

    /** 所属WS链接分组 ID(按分组查)。 */
    private Long labelId;

    /** 所属批次 ID(按批次查)。 */
    private Long batchId;

    /**
     * 导入结果过滤:1=成功 2=失败。
     *
     * <p>兼容旧四态筛选:3=重复、4=格式错误会转换为 {@code result=2} + {@code failReason}。</p>
     */
    private Integer result;

    /** 失败原因过滤:重复/格式错误;通常配合 result=2 使用。 */
    private String failReason;

    /** 服务端注入的数据范围；不接受 HTTP 参数绑定。 */
    private DataScope dataScope;

    public DataScope getDataScope() {
        return dataScope;
    }

    /** 仅供 Service 注入可信数据范围。 */
    public void applyDataScope(DataScope dataScope) {
        this.dataScope = dataScope;
    }

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
        if (result != null && result == 3) {
            this.result = GroupLinkImportResult.FAILED.code();
            this.failReason = GroupLinkImportFailReason.DUPLICATE;
            return;
        }
        if (result != null && result == 4) {
            this.result = GroupLinkImportResult.FAILED.code();
            this.failReason = GroupLinkImportFailReason.FORMAT_ERROR;
            return;
        }
        this.result = result;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }
}
