# 五段号与六段号兼容导入设计

- 日期：2026-08-06
- 状态：已确认，待实施
- 需求来源：用户确认控台采用兼容入口，五段号可直接导入并走 Android 自动上线

## 1. 背景与当前事实

当前账号导入格式 `importFormat=1` 名为“六段号”，后端只接受以下六列：

```text
phone,static_pub_key,static_pri_key,id_pub_key,id_pri_key,phone_id
```

用户提供的五段号缺少最后一列 `phone_id`。现有 `AccountImportParser` 会将其判为格式错误，而 Android 上线命令在协议边界仍要求完整的六字段凭据。已在第一套测试环境验证：为五段号补充独立 `phone_id` 后，可复用现有 Android 导入、自动上线、代理分配和状态回写链路成功上线。

## 2. 目标

- 控台同一个入口同时接受五段号和六段号。
- 五段号由后端生成唯一 `phone_id`，转换成完整六段运行时凭据。
- 六段号保持现有行为，不改写调用方提供的 `phone_id`。
- 继续使用 `importFormat=1`、`credFormat=1` 和 `protocolId=ANDROID`。
- 保留五段号原始输入，以五列形式导出。
- 不修改数据库结构、Android 协议契约和自动上线调度机制。

## 3. 非目标

- 不新增独立的“五段号”导入格式编码。
- 不改变 JSON 号或全参账号的解析规则。
- 不增加新的立即上线接口或独立调度器。
- 不在本次兼容中新增 Base64 长度、密钥配对等更严格的业务校验；继续沿用现有协议层校验边界。
- 不回填或改写历史导入批次。

## 4. 方案选择

### 4.1 采用：现有格式兼容五列和六列

保留 `importFormat=1`。后端按列数识别：

- 五列：生成 `phone_id`，构造六字段凭据。
- 六列：沿用原始六字段凭据。
- 其他列数：返回逐行格式错误。

该方案不需要数据库迁移，也不增加协议路由分支；历史六段号调用方保持兼容。

### 4.2 否决：新增独立五段号格式编码

需要增加枚举、接口映射、查询筛选、展示和运行时格式映射，收益仅是区分来源类型。原始五列内容已由 `account_import_detail.raw_payload` 保留，因此不为来源展示增加第二套运行时格式。

### 4.3 否决：仅由前端补 `phone_id`

上传脚本或其他 API 客户端直接调用后端时仍会失败，并且凭据规范化逻辑会分散在多个调用端。生成和兼容逻辑必须位于后端解析边界。

## 5. 后端设计

### 5.1 解析规则

`AccountImportParser.parseSixLine` 接受两种输入：

```text
五列：phone,static_pub_key,static_pri_key,id_pub_key,id_pri_key
六列：phone,static_pub_key,static_pri_key,id_pub_key,id_pri_key,phone_id
```

共享现有校验：

- 手机号必须是 7 至 15 位纯数字。
- 密钥列不得为空。
- 六列输入的 `phone_id` 不得为空。

五列输入使用 `UUID.randomUUID()` 生成 32 位小写十六进制 `phone_id`。生成值只进入规范化后的凭据 JSON，不拼回 `raw_payload`。

解析成功后，两种输入都生成统一对象：

```json
{
  "phone": "...",
  "static_pub_key": "...",
  "static_pri_key": "...",
  "id_pub_key": "...",
  "id_pri_key": "...",
  "phone_id": "..."
}
```

### 5.2 持久化与导出

- `account.protocol_id` 继续写 `ANDROID`。
- `account_credential.cred_format` 继续写 `1`。
- `account_credential.creds_json` 保存规范化后的完整六字段对象。
- `account_import_detail.raw_payload` 保留原始行：五段输入仍保存五列，六段输入仍保存六列。
- 现有 TXT 导出直接读取原始 payload，因此五段号导出仍为五列。

不新增表、列、索引或 Flyway 迁移。

### 5.3 上线链路

导入成功行继续写入 `online_phase=QUEUED`。现有调度器创建不含敏感凭据的 `account.online.requested` outbox，并记录 `protocolBackend=ANDROID` 与 `credentialFormat=SIX_SEGMENT`；发布器发送 Kafka 命令前再从凭据表补齐完整六字段凭据。Android 协议层继续接收完整六字段凭据，不需要修改。

### 5.4 错误处理与安全

- 非五列、非六列：逐行提示“应为 5 列或 6 列”。
- 五列或六列中的必填列为空：沿用逐列非空错误。
- 生成 `phone_id` 不失败时不增加新的导入异常类型；UUID 生成使用 JDK 标准实现。
- 日志继续只记录脱敏手机号、行号和凭据长度，不记录原始密钥、规范化凭据或导入原文。

## 6. 前端设计

- 导入入口、筛选项和批次展示文案由“六段号”调整为“五/六段号”。
- 说明文案明确支持粘贴或上传 TXT，一行一个五段号或六段号。
- API 仍提交 `importFormat=1`。
- 映射层同时识别历史文案“六段号”和新文案“五/六段号”，避免旧状态或调用代码失配。
- 不在浏览器生成 `phone_id`，不改变表单字段和接口请求结构。

## 7. 影响范围

后端预计涉及：

- `AccountImportParser`
- `ImportFormat` 的注释和接口说明
- `AccountImportParserTest`
- `AccountImportServiceImplDbTest`
- `AccountImportOnlineDispatcherDbTest`
- 必要的 Controller 契约测试

前端预计涉及：

- 账号导入常量与说明文案
- `account-import.ts` 的格式标签映射
- 对应常量、API 和导入抽屉测试

协议层无代码改动。

## 8. 验证设计

### 8.1 后端单元测试

- 五列输入解析成功，凭据对象包含生成的 `phone_id`。
- 同批多条五段号生成的 `phone_id` 非空、格式正确且互不相同。
- 六列输入的 `phone_id` 原样保留。
- 四列、七列和空必填列返回明确格式错误。
- 原始五列 `raw_payload` 不被补成六列。

### 8.2 后端数据与调度测试

- 五段号导入后 `protocol_id=ANDROID`、`cred_format=1`。
- 凭据表保存完整六字段 JSON，导出仍返回原始五列。
- 自动上线 outbox 使用 `protocol_backend=ANDROID` 和 `credentialFormat=SIX_SEGMENT`，并继续不持久化任何凭据值。
- 凭据表中的生成 `phone_id` 会由现有发布器补入最终 Kafka 命令信封；发布器六段凭据契约测试保持通过。
- 六段号原有导入和自动上线测试保持通过。

### 8.3 前端测试

- 入口和提示展示“五/六段号”。
- 新旧文案都映射为 `importFormat=1`。
- 粘贴和 TXT 上传请求结构不变。
- TypeScript、Vue 类型检查和生产构建通过。

### 8.4 测试环境验收

- 分别导入至少一条五段号和一条六段号。
- 确认两条均进入目标账号分组并走 Android。
- 核对协议状态事件、Armada `account_state.login_state` 和 outbox 三层事实。
- 导出批次，确认五段原样导出、六段原样导出。

## 9. 部署与回滚

先部署后端，再部署前端。后端上线后旧前端仍提交 `importFormat=1`，因此部署窗口兼容；前端仅更新展示文案。

回滚时恢复后端解析器和前端文案即可。无数据库迁移，无数据回滚脚本；已导入的五段号在凭据表中已保存为完整六段，回滚后仍可继续上下线。
