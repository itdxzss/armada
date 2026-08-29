package com.armada.contact.task.model.dto;

/**
 * 通讯录营销任务列表查询条件。
 *
 * @param name 任务名模糊匹配
 * @param runStatus 运行状态精确匹配
 * @param createdAtStart 创建时间起（epoch 毫秒）
 * @param createdAtEnd 创建时间止（epoch 毫秒）
 * @param page 页码，从 1 开始
 * @param pageSize 每页条数
 */
public record ContactTaskQuery(
        String name,
        Integer runStatus,
        Long createdAtStart,
        Long createdAtEnd,
        Integer page,
        Integer pageSize
) {

    /** 未传页码时默认第 1 页。 */
    public int pageOrDefault() {
        return page == null || page < 1 ? 1 : page;
    }

    /** 未传每页条数时默认 20，上限 200（与竞品分页选项一致）。 */
    public int pageSizeOrDefault() {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 200);
    }

    /** 下推数据库的偏移量。 */
    public int offset() {
        return (pageOrDefault() - 1) * pageSizeOrDefault();
    }
}
