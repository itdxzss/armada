package com.armada.platform.wbpro.model;

/**
 * 一个可导入的 WB 环境。
 *
 * @param envId WB 环境标识，与 WB 的 WBPRO_ENV_ID 一致
 * @param name 页面显示名
 * @param baseUrl WB 后端地址，生产必须 https
 * @param clientId 本 Armada 环境在该 WB 登记的接入方
 * @param secret HMAC 密钥，不得输出
 */
public record WbproTarget(String envId, String name, String baseUrl, String clientId, String secret) {

    @Override
    public String toString() {
        return "WbproTarget[envId=" + envId + ", clientId=" + clientId + "]";
    }
}
