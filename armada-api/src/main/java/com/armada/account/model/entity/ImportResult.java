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
    SUCCESS(1),

    /**
     * 重复:批内 wid 重复或库内唯一键冲突(uq_tenant_phone),账号未写入。
     * parse_result = 2。
     */
    DUPLICATE(2),

    /**
     * 格式错误:JSON 解析失败、wid 不合法等通用解析异常,账号未写入。
     * parse_result = 3。
     */
    FORMAT_ERROR(3),

    /**
     * 凭据不全:缺少 Baileys 必需字段(registrationId/noiseKey 等),账号未写入。
     * parse_result = 4。
     */
    CRED_INCOMPLETE(4);

    /** {@code account_import_detail.parse_result} 存储编码。 */
    private final int code;

    ImportResult(int code) {
        this.code = code;
    }

    /**
     * 获取数据库存储编码。
     *
     * @return parse_result 整型值
     */
    public int getCode() {
        return code;
    }
}
