package com.armada.hyperlink.data.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 数据包 TXT 导入模式。 */
public enum DataPackageImportMode {

    /** 向当前代追加包内不存在的号码。 */
    APPEND(1),

    /** 写入下一代号码并原子切换当前代。 */
    OVERWRITE(2);

    private final int code;

    DataPackageImportMode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 按 API 大小写敏感枚举解析模式。 */
    public static DataPackageImportMode fromApi(String value) {
        if (value != null) {
            for (DataPackageImportMode mode : values()) {
                if (mode.name().equals(value)) {
                    return mode;
                }
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "导入模式必须为 APPEND 或 OVERWRITE");
    }
}
