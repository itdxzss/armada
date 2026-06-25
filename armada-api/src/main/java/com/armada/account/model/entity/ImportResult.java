package com.armada.account.model.entity;

/**
 * 单行导入结果枚举。
 *
 * <p>对应 {@code account_import_detail.parse_result} 存储编码,
 * 也用于 {@code AccountImportServiceImpl} 循环内部分类计数。</p>
 */
public enum ImportResult {

    /**
     * 成功入库:三步原子写(account + account_state + account_credential)全部完成。
     * parse_result = 1。
     */
    SUCCESS(1, "成功入库"),

    /**
     * 重复:批内 wid 重复或库内唯一键冲突(uq_tenant_phone),账号未写入。
     * parse_result = 2。
     */
    DUPLICATE(2, "重复"),

    /**
     * 格式错误:JSON 解析失败、wid 不合法等通用解析异常,账号未写入。
     * parse_result = 3。
     */
    FORMAT_ERROR(3, "格式错误"),

    /**
     * 凭据不全:缺少 Baileys 必需字段(registrationId/noiseKey 等),账号未写入。
     * parse_result = 4。
     */
    CRED_INCOMPLETE(4, "凭据不全");

    /** {@code account_import_detail.parse_result} 存储编码。 */
    private final int code;

    /** CSV 导出/前端展示用中文标签。 */
    private final String label;

    ImportResult(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 获取数据库存储编码。
     *
     * @return parse_result 整型值
     */
    public int getCode() {
        return code;
    }

    /**
     * 获取中文标签(CSV 导出 / 前端展示)。
     *
     * @return 中文标签,如 "成功入库"、"重复"、"格式错误"、"凭据不全"
     */
    public String getLabel() {
        return label;
    }

    /**
     * 按存储编码查找对应枚举值;未匹配时返回 null。
     *
     * @param code parse_result 整型值
     * @return 对应枚举,或 null
     */
    public static ImportResult fromCode(int code) {
        for (ImportResult r : values()) {
            if (r.code == code) {
                return r;
            }
        }
        return null;
    }
}
