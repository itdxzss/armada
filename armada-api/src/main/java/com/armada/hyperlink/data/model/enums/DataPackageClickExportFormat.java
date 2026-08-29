package com.armada.hyperlink.data.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 竞品批量点击记录支持的文件格式。 */
public enum DataPackageClickExportFormat {

    /** 仅包含收件人手机号。 */
    TXT("txt"),

    /** 包含数据包和点击上下文列。 */
    CSV("csv");

    private final String apiValue;

    DataPackageClickExportFormat(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }

    /** 把请求值解析为稳定格式。 */
    public static DataPackageClickExportFormat fromApi(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        for (DataPackageClickExportFormat format : values()) {
            if (format.apiValue.equals(normalized)) {
                return format;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "点击记录导出格式仅支持 txt 或 csv");
    }
}
