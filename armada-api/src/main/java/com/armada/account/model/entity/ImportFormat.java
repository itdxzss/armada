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
     * Android 五段或六段 CSV 格式:
     * phone,static_pub_key,static_pri_key,id_pub_key,id_pri_key[,phone_id]。
     * 五段输入运行时补齐 phone_id,仍按六字段 Android 凭据处理。
     */
    SIX(1),

    /**
     * Baileys JSON 格式:每条为 wheel 在用的裸 creds JSON 对象,必需字段在顶层。
     * 支持单对象、JSON 数组、.zip 压缩包(一号一文件)三种封装方式。
     */
    JSON(2),

    /**
     * 全参账号格式:TXT 或粘贴文本中每个非空行一个 JSON 对象。
     * Android 导入时转换为六字段运行时凭据；iOS 导入保留原生完整对象，
     * 批次均保留 PARAMS 来源格式。
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
