package com.armada.account.model.entity;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/**
 * 账号导入文件格式枚举。
 *
 * <p>决定解析器选用哪条路径处理导入文件。格式由前端上传时显式传入,
 * 后端按此枚举分发到对应解析逻辑,不自动猜测。</p>
 */
public enum ImportFormat {

    /**
     * 六段式 CSV 格式(phone,password,email,token,session,device)。
     * 当前协议层尚未接通六段解析,导入直接整体拒绝。
     */
    SIX(1),

    /**
     * Baileys JSON 格式:每条为 wheel 在用的裸 creds JSON 对象,必需字段在顶层。
     * 支持单对象、JSON 数组、.zip 压缩包(一号一文件)三种封装方式。
     */
    JSON(2),

    /**
     * Params 键值 JSON 格式:扁平键值对象,必须含 {@code wid} 等必需字段。
     * 支持单对象和 JSON 数组两种封装方式。
     */
    PARAMS(3);

    /** 数据库/接口编码值。 */
    private final int code;

    ImportFormat(int code) {
        this.code = code;
    }

    /**
     * 获取数值编码。
     *
     * @return 接口/存储层使用的整型编码
     */
    public int getCode() {
        return code;
    }

    /**
     * 按数值编码反查枚举值。
     *
     * @param code 整型编码
     * @return 对应枚举值
     * @throws com.armada.shared.exception.BusinessException 如果编码不存在(VALIDATION 错误码,可恢复业务异常)
     */
    public static ImportFormat fromCode(int code) {
        for (ImportFormat f : values()) {
            if (f.code == code) {
                return f;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "未知导入格式编码: " + code);
    }
}
