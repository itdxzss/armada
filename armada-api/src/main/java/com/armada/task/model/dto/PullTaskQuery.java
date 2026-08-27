package com.armada.task.model.dto;

import com.armada.shared.paging.PageQuery;
import com.armada.shared.security.DataScope;
import com.armada.task.model.enums.PullTaskGroupSource;
import com.armada.task.model.enums.PullTaskType;

/** 拉群任务统一列表查询参数，供 {@code @ModelAttribute} 绑定。 */
public class PullTaskQuery extends PageQuery {

    /** 任务 ID 精确值。 */
    private Long id;

    /** 任务名称或群名称关键字。 */
    private String keyword;

    /** 普通或拉群营销任务状态码。 */
    private String status;

    /** 公共任务类型。 */
    private PullTaskType taskType;

    /** 拉群营销群组来源。 */
    private PullTaskGroupSource groupSource;

    /** 操作员展示名关键字。 */
    private String operator;

    /** 服务端从可信认证身份注入的数据范围，不参与 HTTP 参数绑定。 */
    private DataScope dataScope;

    /**
     * 转为 Mapper 共享的不可变筛选对象。
     *
     * @return 已把空白文本归一为 {@code null} 的筛选条件
     */
    public PullTaskFilter toFilter() {
        return new PullTaskFilter(
                id, normalize(keyword), normalize(status), taskType, groupSource,
                normalize(operator), dataScope);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public PullTaskType getTaskType() {
        return taskType;
    }

    public void setTaskType(PullTaskType taskType) {
        this.taskType = taskType;
    }

    public PullTaskGroupSource getGroupSource() {
        return groupSource;
    }

    public void setGroupSource(PullTaskGroupSource groupSource) {
        this.groupSource = groupSource;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public DataScope getDataScope() {
        return dataScope;
    }

    /** 仅供 Service 注入服务端数据范围。 */
    public void applyDataScope(DataScope dataScope) {
        this.dataScope = dataScope;
    }
}
