package com.armada.shared.response;

import java.util.List;

/**
 * 统一分页返回类型。分页接口对外一律返回此类型,禁止各业务自造分页 DTO。
 *
 * @param <T> 列表元素类型
 */
public record PageResult<T>(

        /** 当前页数据列表。 */
        List<T> list,

        /** 页码(从 1 起)。 */
        int page,

        /** 每页条数。 */
        int pageSize,

        /** 总条数。 */
        long total,

        /** 总页数(由 total 与 pageSize 推导,不手算)。 */
        int totalPages) {

    /**
     * 构造分页结果,{@code totalPages} 自动推导。
     *
     * @param list     当前页数据
     * @param page     页码
     * @param pageSize 每页条数
     * @param total    总条数
     * @return 分页结果
     */
    public static <T> PageResult<T> of(List<T> list, int page, int pageSize, long total) {
        int totalPages = pageSize <= 0 ? 0 : (int) ((total + pageSize - 1) / pageSize);
        return new PageResult<>(list, page, pageSize, total, totalPages);
    }
}
