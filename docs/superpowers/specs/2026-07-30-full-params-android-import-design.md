# 全参账号转六段并走 Android 协议导入设计

> 状态：本地实现与验证已完成，待测试环境联调
> 日期：2026-07-30
> 范围：`armada` 账号导入后端、`wheel-saas-pure-web` 账号导入前端；`armada-protocol` 不改代码

## 1. 背景与目标

账号导入页已经预留“全参账号”类型，接口与数据库也已预留 `import_format=3`，但当前前端禁用了入口，后端全参解析仅支持整个文本为单对象或数组并要求 `wid`，写入时还会把凭据保存成 `cred_format=3`、协议留空。因此，把现有全参文件直接导入后不会走已验证的 Android 六段登录链路。

本次目标：业务人员选择“全参账号”后，可粘贴或上传 TXT 格式的逐行 JSON 全参数据；系统逐行转换成 Android 六段凭据并入库，成功账号沿用现有 10 秒账号导入调度自动上线，同时导出仍返回原始全参 JSON 行。

三份样本文件只做字段结构核验，不记录任何凭据值：

- `7-21飞行非洲全参.txt`：120 行；
- `7-28佛爷-非混全参-50.txt`：50 行；
- `7-29-小米混合全参50.txt`：实际 40 行；
- 共 210 行，六段转换所需字段均完整。

## 2. 已确认的产品口径

- 导入类型由业务人员手动选择“全参账号”，后端不自动猜格式。
- 输入只支持 TXT 文件或粘贴文本；每个非空行必须是一个 JSON 对象（NDJSON）。
- 全参账号的接入协议固定为 `ANDROID`。
- “机型”继续由业务人员手动选择，写入 `account.device_os`，仅用于业务展示和筛选；即使选择“苹果”，协议仍固定走 Android。
- 导入时转换，账号凭据只保存可直接上线的六段结构；不在上线阶段重复转换。
- 批次与明细保留全参来源，导出继续返回原始全参 JSON 行，不导出转换后的六段凭据。
- 单行失败不影响同批其他行：合法行正常入库，非法行记录失败原因。
- 导入成功账号复用现有 10 秒调度，不增加导入后立即派发逻辑。
- 账号列表继续按手工选择的机型展示；本次不新增“全参来源”列。导入批次列表仍可通过“导入类型=全参账号”区分来源。

## 3. 数据语义与存储

本次不新增表或字段，也不修改现有枚举编码。现有字段分别承担不同事实：

| 存储位置 | 写入值 | 业务含义 |
|---|---:|---|
| `account_import_batch.import_format` | `3`（PARAMS） | 原始导入来源是全参账号，用于批次筛选、展示和原格式导出 |
| `account_import_detail.raw_payload` | 对应的原始 JSON 行 | 原格式导出的敏感原文，不进入普通列表响应或日志 |
| `account_import_detail.source_entry_name` | `params-input[行号]` 或等价稳定名称 | 定位原始行和导出条目 |
| `account_credential.cred_format` | `1`（SIX） | 实际上线凭据已经规范化为六段格式 |
| `account_credential.creds_json` | 六段 JSON 对象 | Android 协议直接使用的登录凭据 |
| `account.protocol_id` | `ANDROID` | 固定路由到 Android 协议节点 |
| `account.device_os` | 前端人工选择值 | 账号列表的机型展示和筛选，不参与协议路由 |

这里不新增“原始账号类型”字段：批次的 `import_format=3` 已是全参来源的唯一事实，账号运行时只需要规范化凭据和协议。原文只在导入明细保存一份，避免在 `account_credential` 再保存一套会产生分歧的全参凭据。

## 4. 后端组件设计

### 4.1 全参解析

`AccountImportParser` 的 PARAMS 分支改为逐行解析：

1. 从粘贴文本或 TXT 字节取得 UTF-8 文本。
2. 按换行拆分，忽略空白行，每个非空行独立调用 JSON 解析。
3. 每行只能是 JSON 对象；数组、标量或非法 JSON 只标记该行格式错误，不中断整批。
4. 原始行原样写入 `ParsedEntry.rawPayload`，不重新序列化，以保证导出格式保持不变。
5. 把对象交给独立转换器校验和转换；成功时把手机号和六段 JSON 放入 `ParsedEntry`，失败时写入不含密钥值的明确错误原因。

全空输入继续整批拒绝；只要存在非空行，即建立批次并逐行统计成功、重复、格式错误或凭据不全。

### 4.2 `FullParamsToSixConverter`

新增无数据库、无网络、无日志副作用的纯转换组件，职责仅限于校验单个全参对象并生成规范化六段对象。字段映射固定如下：

| 全参字段 | 六段字段 |
|---|---|
| `phone` | `phone` |
| `clientStaticPublicKey` | `static_pub_key` |
| `clientStaticPrivateKey` | `static_pri_key` |
| `identityPublicKey` | `id_pub_key` |
| `identityPrivateKey` | `id_pri_key` |
| `phoneUUID` | `phone_id` |

校验规则：

- `phone` 必须是 7～15 位纯数字；如果存在 `jid`，它必须与 `phone` 一致；
- 其余五个字段必须存在、类型为字符串且去除首尾空白后非空；
- 错误消息只包含字段名和行号，不包含字段值；
- `signedPreKey`、`registrationID/registrationId`、iOS 机型与系统版本等字段不进入六段凭据，也不参与上线；它们仍保留在 `raw_payload` 供原格式导出。

独立转换器避免把字段映射散落在解析器或写库逻辑中，并允许对转换契约做直接单元测试。

### 4.3 账号与凭据写入

`AccountImportRowWriter` 不能再把“导入来源格式”直接同时当作“运行时凭据格式”。写入规则调整为：

- `SIX` 导入：保持现状，`protocol_id=ANDROID`、`cred_format=SIX`；
- `JSON` 导入：保持现状，协议默认 WEB、`cred_format=JSON`；
- `PARAMS` 导入：新增固定映射，`protocol_id=ANDROID`、`cred_format=SIX`；
- `creds_json` 继续序列化 `ParsedEntry.data`。PARAMS 解析成功后的 `data` 已经是转换后的六段对象，因此不会把全参原文写入凭据表。

账号、账号状态、账号凭据仍在现有单行事务中原子写入；库内重复继续依赖租户内手机号唯一键处理。

## 5. 自动上线数据流

本次完全复用 JSON 号和六段号现有的导入上线机制：

```text
合法全参行
  → 转换为六段并写 account/account_state/account_credential
  → account_import_detail.online_phase = QUEUED
  → 现有调度器每 10 秒扫描一次
  → AccountImportOnlineDispatchWorker 批量调用 onlineBatch
  → protocol_id=ANDROID + cred_format=SIX
  → 发送 SIX_SEGMENT 上线命令到 Android 协议
```

非法、凭据不全或重复行写 `online_phase=SKIPPED`，不参与调度。合法账号的理论等待为 0～10 秒，平均约 5 秒。现有每批最多 500 条、派发重试、状态回写和登录结果冻结逻辑均保持不变。

本次不增加新的调度器、不修改 10 秒配置、不增加 after-commit 立即派发，也不修改 Android 协议服务。

## 6. 前端交互

账号导入抽屉沿用现有结构：

- 启用已经预留的“全参账号”单选项；
- 接受 `.txt` 文件或直接粘贴文本；
- 提示文案明确“一行一个 JSON 对象；上线协议固定为 Android”；
- 机型单选继续显示“安卓 / 苹果”，不联动、不禁用，保留业务人员手动选择；
- 提交时沿用现有导入接口，发送 `importFormat=3`、文本内容、原文件名、分组、机型、账号类型和 IP 分配方式；
- 导入成功提示、批次列表、明细抽屉和导出入口保持现状。

不新增页面、接口或前端状态管理；仅打开预留入口并补充约束提示。

## 7. 原格式导出

现有导出链路已经按 `account_import_detail.raw_payload` 生成 TXT，本次沿用：

- 全量、成功、失败范围仍按导入明细筛选；
- 按 `line_no` 排序，每条原始全参 JSON 占一行；
- 输出内容来自原始 `raw_payload`，不能读取或反向拼装 `account_credential.creds_json`；
- 文件扩展名和 Content-Type 仍为 `.txt` / `text/plain;charset=UTF-8`；
- 不在导出内容中增加六段字段、状态字段或表头。

## 8. 错误处理与安全

- 非法 JSON：该行记格式错误，原因包含行号，不回显整行内容。
- 非对象 JSON：该行记“全参必须为 JSON 对象”。
- 缺少或清空六段所需字段：该行记凭据不全，并指出缺失字段名。
- `phone` 不合法，或可选的 `jid` 与 `phone` 不一致：该行记格式错误，不创建账号。
- 批内或库内手机号重复：沿用现有重复口径，不创建第二个账号。
- 全部行失败：仍创建已完成批次和失败明细，便于用户查看及导出失败原文；全空内容才整批拒绝。
- 日志禁止输出全参原文、六段密钥、`raw_payload` 或 `creds_json`；只允许批次 ID、行号、计数、脱敏手机号和凭据长度。
- 普通批次/明细/账号列表接口不新增敏感字段；原文只通过已有受权限控制的导出接口返回。

## 9. API、数据与跨仓影响

### API

不新增接口，不改变 multipart 字段和响应结构。`importFormat=3` 从“预留但不可用”变为正式支持的全参 NDJSON 语义。

### 数据库

不做 Flyway 迁移。复用 `account_import_batch.import_format`、`account_import_detail.raw_payload`、`account_credential.cred_format/creds_json` 和 `account.protocol_id/device_os`。

### 租户隔离

所有写入继续经过现有账号导入 Service、Mapper 和租户拦截器；不增加跨租户查询或忽略租户注解。

### 协议层

无代码改动。后端发送的仍是 Android 已支持并经过样本验证的 `format=six` 六段凭据。

## 10. 测试与验收

### 后端单元测试

- 转换器逐项验证六个字段映射正确；
- 缺少、空白或类型错误的每个必需字段均返回对应错误；
- 非法 `phone` 以及 `jid`/`phone` 不一致被拒绝；
- 额外全参字段不影响转换，也不会进入六段对象；
- 测试断言和失败输出不得包含真实样本密钥。

### 解析器测试

- 多行 NDJSON 逐行产出，空行忽略；
- 单行非法 JSON 不影响相邻合法行；
- 数组或标量行被单独拒绝；
- 每条 `raw_payload` 与输入原始行一致；
- 三份样本可用脱敏结构夹具覆盖字段变体，但不能把真实凭据提交到仓库。

### 后端数据库与服务测试

- 全参成功行写 `batch.import_format=3`；
- `account.protocol_id=ANDROID`，`account.device_os` 等于人工选择值；
- `account_credential.cred_format=1`，`creds_json` 只包含六段结构；
- 明细保存原始全参行并置为 `QUEUED`；失败行置为 `SKIPPED`；
- 合法与非法行混合时计数、明细和部分成功符合现有口径；
- 导出全部/成功/失败均返回对应原始全参行；
- 自动上线派发读取到的命令格式为 `SIX_SEGMENT`，协议路由为 Android。

### 前端测试

- “全参账号”选项可选择，不再禁用；
- 只接受 TXT 或粘贴内容，空内容阻止提交；
- 提交参数包含 `importFormat=3` 和人工选择的机型；
- 页面明确提示 Android 协议固定，但不会强制改写机型；
- JSON 号、六段号既有导入行为不回归。

### 验收标准

在测试环境导入至少一个包含合法与非法行的全参批次：合法账号应在现有 10 秒调度周期内进入 Android 上线链路，非法行可在导入明细看到脱敏原因；导出该批次后，内容仍为原始全参 JSON，而不是六段 JSON。

## 11. 发布与回滚

发布顺序：先发布后端解析与转换能力，再发布前端启用入口；协议服务无需发布。这样前端入口开放时后端已经具备完整处理能力。

如需回滚：先重新禁用前端全参入口，再回退后端变更。已经成功导入的账号保存的是标准六段凭据和 Android 协议，后端回滚后仍可按现有六段链路上线；已保存的批次与原始导出数据不需要清理，也不得自动删除。

## 12. 决策记录

| 议题 | 结论 |
|---|---|
| 转换时机 | 导入时一次性转换，不在每次上线时转换 |
| 转换器 | 使用独立纯组件 `FullParamsToSixConverter` |
| 输入格式 | TXT/粘贴，逐行 JSON 对象（NDJSON） |
| 协议 | 固定 Android |
| 机型 | 业务人员手动选择，只用于展示/筛选 |
| 凭据存储 | 保存转换后的六段凭据，`cred_format=1` |
| 来源与导出 | 批次保留 `import_format=3`，明细保留原始 JSON 行 |
| 部分失败 | 逐行失败，合法行继续导入和上线 |
| 自动上线 | 复用现有 10 秒调度，不加立即派发 |
| 协议层改动 | 无 |
