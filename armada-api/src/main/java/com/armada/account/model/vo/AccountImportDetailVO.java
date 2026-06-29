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
 * @param loginResult      登录结果:null=未登录(step1);1成功 2失败 3密钥异常 4封号
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
        Long createdAt,
        String groupName
) {
}
