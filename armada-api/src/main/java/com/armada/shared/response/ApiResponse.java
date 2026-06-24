package com.armada.shared.response;

/**
 * 统一响应结构 {@code {code, message, data}}。{@code code=0} 表示成功,非 0 表示业务错误。
 */
public record ApiResponse<T>(

        /** 业务码:0=成功,非 0=业务错误码。 */
        int code,

        /** 提示信息。 */
        String message,

        /** 业务数据;失败时为 null。 */
        T data) {

    private static final int SUCCESS_CODE = 0;
    private static final String SUCCESS_MESSAGE = "ok";

    /** 成功并携带数据。 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    /** 成功无数据。 */
    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    /** 业务错误,携带错误码与提示。 */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
