package com.armada.account.model.vo;

import com.armada.account.model.enums.AccountMessageCapQueryStatus;

/**
 * 批量额度查询中单个账号的结果。
 *
 * @param accountId 账号 ID
 * @param phone 账号手机号；账号不可用时为空
 * @param backend 协议后端 WEB / ANDROID；账号不可用时为空
 * @param queryStatus 本次查询的处理结果
 * @param errorCode 仅 FAILED 时给出协议异常类型，如 TIMEOUT、NETWORK
 * @param cap 额度快照；仅 SUCCESS 和 REJECTED 时存在
 */
public record AccountMessageCapItemVO(
        Long accountId,
        String phone,
        String backend,
        AccountMessageCapQueryStatus queryStatus,
        String errorCode,
        AccountMessageCapVO cap) {
}
