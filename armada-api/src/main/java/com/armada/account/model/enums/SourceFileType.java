package com.armada.account.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/**
 * 账号导入原始来源容器类型。
 */
public final class SourceFileType {

    private SourceFileType() {
    }

    /** ZIP 文件导入。 */
    public static final String ZIP = "ZIP";

    /** TXT/粘贴文本导入。 */
    public static final String TXT = "TXT";

    /**
     * 校验批次是否具备按原格式导出的来源类型。
     *
     * @param value 数据库存储值
     * @return 标准化后的来源类型
     */
    public static String requireSupported(String value) {
        if (ZIP.equals(value) || TXT.equals(value)) {
            return value;
        }
        throw new BusinessException(ErrorCode.VALIDATION, "该批次缺少原始导出材料");
    }
}
