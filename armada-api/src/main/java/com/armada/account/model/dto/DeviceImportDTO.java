package com.armada.account.model.dto;

/**
 * 手机直传入参；payload 只在导入内存和受保护的凭据表中使用。
 * @param groupName 手机手工输入的目标分组名称，由服务端在令牌租户内解析
 * @param phone 纯数字账号，与全参 phone 及电话 jid 的号码部分一致
 * @param payload 手机复制全参产生的 JSON 单行原文
 */
public record DeviceImportDTO(String groupName, String phone, String payload) {

    /** 避免框架或诊断调用字符串表示时泄漏请求内容。 */
    @Override
    public String toString() {
        return "DeviceImportDTO[redacted]";
    }
}
