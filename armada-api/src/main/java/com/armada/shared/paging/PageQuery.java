package com.armada.shared.paging;

/**
 * 分页查询基类。所有列表查询对象 {@code extends PageQuery}。
 *
 * <p>用 {@code @ModelAttribute} 绑定 GET 查询参数,绑定走 setter,故必须是可变 class(不能用 record)。
 * setter 内对 page / pageSize 做钳制,防止恶意/异常分页参数。</p>
 */
public class PageQuery {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 1000;

    /** 页码,从 1 起;小于 1 回退为 1。 */
    private int page = DEFAULT_PAGE;

    /** 每页条数;非正回退默认 10,超过上限截断为 1000。 */
    private int pageSize = DEFAULT_PAGE_SIZE;

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page < DEFAULT_PAGE ? DEFAULT_PAGE : page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /** SQL 下推用的 OFFSET(同 {@link #offset()};供 MyBatis 反射访问)。 */
    public int getOffset() {
        return (page - 1) * pageSize;
    }

    /** SQL 下推用的 OFFSET。 */
    public int offset() {
        return getOffset();
    }
}
