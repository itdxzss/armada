package com.armada.account.model.dto;

/**
 * 手机直传入参；payload 只在导入内存和受保护的凭据表中使用。
 * @param accountGroupId 手机从分组列表选择的目标分组，必须属于令牌租户且未删除
 * @param phone 纯数字账号，与全参 jid 一致
 * @param payload 手机复制全参产生的 JSON 单行原文
 */
public record DeviceImportDTO(Long accountGroupId, String phone, String payload) {

    /** 避免框架或诊断调用字符串表示时泄漏请求内容。 */
    @Override
    public String toString() {
        return "DeviceImportDTO[redacted]";
    }
}
