# 账号 WS 号码导出 - 规格

## 背景与目标

账号列表支持前端勾选多条账号后批量导出 WS 号码。后端必须只导出当前租户所选账号中状态正常的号码，
并直接返回一个真实的 TXT 下载文件。文件每行一个号码，号码经过数字清洗和去重，前端根据响应头中的实际数量展示成功提示。

本期不实现同一天重复导出时追加 `_2`、`_3` 的文件名序号，因此不新增数据库表或 Flyway 迁移。

## 接口契约

新增接口：

```http
POST /api/accounts/export-ws-phones
Content-Type: application/json
X-Tenant-Code: <当前租户码>
```

请求体：

```json
{
  "ids": [101, 102, 103],
  "groupName": "马来西亚客户组"
}
```

- `ids` 为前端勾选的账号 ID。去除 `null` 和重复值后必须为 1～2000 个。
- `groupName` 为可选的当前分组名称，只影响下载文件名，不参与账号查询。
- Controller 只负责参数接收和下载响应组装；查询、清洗、去重、计数和文件命名均由独立导出 Service 完成。

成功响应直接返回 TXT 字节，不再套 `ApiResponse`：

```text
Content-Type: text/plain;charset=UTF-8
Content-Disposition: attachment; filename*=UTF-8''...
Content-Length: <文件字节数>
X-Export-Count: <最终写入文件的号码数量>
Access-Control-Expose-Headers: Content-Disposition, X-Export-Count
```

前端以 Blob 接收响应。响应为 `text/plain` 时创建文件下载，并使用 `X-Export-Count` 展示：
“导出成功，共导出XX个WS号码。”统计值只计算最终实际写入 TXT 的唯一号码。

业务失败继续沿用项目统一的 `{code, message, data}` JSON 响应。前端收到 JSON 时不得创建下载文件。

## 导出资格与租户隔离

导出查询只处理请求中的账号 ID，不查询或导出当前租户的其他账号。每批查询逻辑等价于：

```sql
SELECT a.id, a.ws_phone
FROM account a
INNER JOIN account_state s
        ON s.account_id = a.id
       AND s.tenant_id = a.tenant_id
WHERE a.deleted_at IS NULL
  AND s.account_state = #{normalAccountState}
  AND a.id IN (...)
ORDER BY a.id
```

- MyBatis 租户拦截器继续为查询注入当前 `tenant_id`，业务 SQL 不手写租户值。
- `normalAccountState` 必须由 Service 传入 `AccountStateCode.NORMAL`，禁止在 Java 代码中使用魔法数字 `2`。
- `INNER JOIN account_state` 使无状态行、状态为空和状态不是正常态的账号自然不命中。
- `a.deleted_at IS NULL` 防止账号在前端勾选后被并发软删除仍遭导出。
- 只投影 `id` 和 `ws_phone`，不读取凭据、代理或其他敏感/无关字段。

## 输入处理与性能边界

Service 按以下顺序处理请求：

1. 校验请求体和 `ids`，过滤 `null`，使用保序集合去除重复 ID。
2. 过滤后的 ID 为空时抛 `BusinessException(ErrorCode.VALIDATION, "账号 ID 列表不能为空")`。
3. 超过 2000 个唯一 ID 时抛 `BusinessException(ErrorCode.VALIDATION, "单次最多导出 2000 个账号")`。
4. 每 500 个 ID 调用一次 Mapper，避免 MySQL `IN` 参数过长，并降低单次数据库与 MyBatis 对象分配压力。
5. 按 Mapper 的稳定 ID 顺序处理返回行；整个请求最多持有 2000 个轻量行和 2000 个唯一号码，使用内存可控。

查询不使用账号列表分页，不接受前端提交的 `wsPhone` 字段，避免前端伪造号码或导出未选账号。

## 号码清洗与去重

每条查询结果按以下顺序处理：

1. `ws_phone` 为 `null` 或空字符串时跳过。
2. 逐字符仅保留 ASCII 数字 `0`～`9`，删除加号、空格、横线、括号、字母和其他非数字字符。
3. 清洗后为空时跳过。
4. 将清洗结果加入 `LinkedHashSet`，使原值不同但清洗后相同的号码只保留一条。
5. 按集合顺序使用 `\n` 连接，每个号码占一行；最后一行不额外生成空行。

清洗只删除字符，不重排、补齐或截断数字。例如 `+60 (12) 345-6789` 导出为 `60123456789`，原国家区号 `60`
完整保留。现有 `AccountImportParser` 已在导入时使用 `^\\d{7,15}$` 校验号码，因此导出不重复执行长度、国家区号或号段校验。

若最终集合为空，抛出：

```java
new BusinessException(
        ErrorCode.VALIDATION,
        "当前所选账号中没有可导出的有效WS号码。"
);
```

此时尚未组装文件响应，因此前端只收到 JSON 错误，不会生成空 TXT 文件。

## 文件命名

请求日期使用 `Asia/Shanghai` 时区和 `yyyy-MM-dd` 格式：

- `groupName` 有有效文本：`<清洗后的分组名称>_YYYY-MM-DD.txt`
- `groupName` 未传、为 `null` 或仅含空白：`全部WS号_YYYY-MM-DD.txt`

文件名清洗规则：

- 去除名称首尾空白。
- 将控制字符以及 Windows 禁用字符 `< > : " / \\ | ? *` 替换为 `_`。
- 清洗后没有有效文本时回退为 `全部WS号`。
- 使用 Spring `ContentDisposition.attachment().filename(filename, UTF_8)` 生成兼容中文文件名的响应头。

本期每次相同分组在同一天导出都返回相同文件名。浏览器如何处理本地同名文件不属于后端范围；自动追加 `_2`、`_3`
作为后续优化，恢复时需要持久化并发安全的导出序号。

## 代码结构

- `AccountWsPhoneExportDTO`：导出请求，包含 `ids` 和可选 `groupName`。
- `AccountWsPhoneExportRow`：Mapper 轻量投影，只包含 `id` 和 `wsPhone`。
- `AccountWsPhoneExportFile`：Service 输出，包含 `filename`、`bytes` 和 `exportedCount`。
- `AccountWsPhoneExportService` / `AccountWsPhoneExportServiceImpl`：输入校验、分批查询、清洗、去重、文件构建和异常转换。
- `AccountMapper` / `AccountMapper.xml`：新增按 ID 和正常账号状态读取 WS 号码的专用查询。
- `AccountController`：新增下载端点并设置文件、统计和跨域暴露响应头。
- `ErrorCode`：新增 `ACCOUNT_WS_PHONE_EXPORT_FAILED(50001, "导出失败，请重新操作。")`。

新增类和公开方法编写 JavaDoc；输入规范化、500 条分片、号码清洗去重、文件名安全处理和异常转换等关键步骤编写对应行内注释。
不添加逐行重复描述代码含义的噪声注释。

## 异常处理

- 空 ID、超过上限和无有效号码属于可恢复业务错误，直接抛 `BusinessException`，由现有全局异常处理器返回统一 JSON。
- Service 必须原样重新抛出已有 `BusinessException`，保留精确业务提示。
- 数据访问、内容构建等其他运行时异常由 Service 记录完整错误堆栈，然后转换为
  `BusinessException(ErrorCode.ACCOUNT_WS_PHONE_EXPORT_FAILED)`；响应只暴露“导出失败，请重新操作。”。
- 文件字节在 Controller 返回 `ResponseEntity` 前完整构建，避免响应头已经提交后才发现业务错误而产生半个 TXT 文件。

## 测试策略

所有新增行为遵循 TDD：先写单个失败测试并确认失败原因，再写最小实现使其通过。

Service 单元测试覆盖：

- `ids` 中的 `null` 和重复值被去除，空列表被拒绝。
- 2000 个唯一 ID 可导出，2001 个唯一 ID 被拒绝。
- 500 条分片边界正确，多个分片不漏查、不重复计数。
- Mapper 每次收到 `AccountStateCode.NORMAL`。
- `+60 (12) 345-6789`、空格、横线、括号、字母和其他字符只保留 ASCII 数字。
- `null`、空串和清洗后为空的号码被跳过。
- 不同原始字符串清洗为同一号码后只导出一行。
- 国家区号、前导零和数字顺序不改变。
- 没有可导出号码时返回精确业务提示。
- 数据访问异常被记录并转换为账号导出失败错误码。
- 正常分组名、空分组名、非法文件名字符和固定日期生成预期文件名。

Mapper/数据库测试覆盖：

- 只返回请求 ID 中未软删除且 `account_state = 2` 的当前租户账号。
- 其他租户、其他状态、无状态行、状态为空和软删除账号均不返回。
- 查询结果只包含 `id/wsPhone` 并按 ID 稳定排序。

Controller 测试覆盖：

- 成功响应的 TXT 字节、UTF-8 Content-Type、中文文件名、Content-Length 和 `X-Export-Count`。
- `Access-Control-Expose-Headers` 包含文件名和数量响应头。
- Service 抛无有效号码异常时由统一异常处理器返回 JSON，响应中没有附件头。
- Service 抛专用导出失败异常时返回“导出失败，请重新操作。”。

完成实现后先运行新增的精确测试，再运行 `armada-api` Maven 全量测试。只有全部命令成功后才声明功能完成。

## 非目标

- 不实现同一天重复导出的 `_2`、`_3` 文件名序号。
- 不新增导出历史、审计表、临时文件或异步任务。
- 不支持按查询条件导出全部匹配账号；本期只接受前端明确勾选的账号 ID。
- 不修改导入校验规则，不回写或修复数据库中的 `ws_phone`。
- 不修改前端代码；后端只提供文件、数量响应头和约定的错误结果。

## 自检

- 无 `TBD`、`TODO` 或未决实现项。
- 接口成功为 TXT、失败为统一 JSON，不存在同一响应体同时承载文件和 JSON 的矛盾。
- 导出范围同时受所选 ID、租户隔离、未软删除和正常状态四层约束。
- 清洗、去重和统计顺序明确，统计数量与 TXT 实际行数一致。
- 导入校验与导出清洗职责不重复：导入保证 7～15 位有效号码，导出只做需求指定的字符清洗。
- 2000 个 ID 上限和 500 条查询分片与现有批量接口规模一致，内存和 SQL 参数量有界。
- 文件重名序号已明确移出本期范围，因此不需要数据库变更。
