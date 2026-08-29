package com.armada.hyperlink.data.model.enums;

/** 数据包导入审计状态。 */
public enum DataPackageImportStatus {

    /** 已创建审计，正在解析或写入。 */
    PROCESSING(1),

    /** 业务事务与审计结果均已成功提交。 */
    SUCCESS(2),

    /** 解析、校验、写入失败或处理中超时。 */
    FAILED(3);

    private final int code;

    DataPackageImportStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
