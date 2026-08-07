package com.armada.account.model.vo;

/**
 * 账号导入明细条目出参 VO。
 *
 * <p>由 {@code AccountImportService.listDetails} 返回,供 Controller 序列化为 JSON。
 * 字段全部 camelCase(全局无 Jackson 命名策略,默认 camelCase)。</p>
 *
 * @param id               明细主键
 * @param lineNo           行号
 * @param wsPhone          WA 账号号码
 * @param accountId        成功入库时关联的 account.id;失败为 null
 * @param parseResult      解析结果编码:1成功入库 2重复 3格式错误 4凭据不全
 * @param parseResultLabel 解析结果中文标签
 * @param failReason       失败原因;成功时为 null
 * @param loginResult      首次上线结果:null=尚未结算;1成功 2失败 3密钥异常 4封号
 * @param onlinePhase      导入上线阶段:0跳过 1待派发 2已派发待回写 3已结算
 * @param loginReason      首次上线失败或异常原因;成功/未结算时通常为 null
 * @param accountState     当前账号状态:1新增 2正常 3封禁 4导出 5解绑 6被抢登 7抢登中 8账号受限
 * @param loginState       当前登录状态:1在线 2离线 3待上线;未上报时为 null
 * @param accountStateReason 当前账号状态原因,目前来自 account_state.block_reason
 * @param createdAt        创建时间(epoch 毫秒)
 * @param groupName        所属分组名称
 */
public record AccountImportDetailVO(
        Long id,
        int lineNo,
        String wsPhone,
        Long accountId,
        int parseResult,
        String parseResultLabel,
        String failReason,
        Integer loginResult,
        Integer onlinePhase,
        String loginReason,
        Integer accountState,
        Integer loginState,
        String accountStateReason,
        Long createdAt,
        String groupName
) {
}
