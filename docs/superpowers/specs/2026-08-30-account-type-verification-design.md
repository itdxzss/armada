# WhatsApp 账号类型上线校验设计

## 目标

保留导入时的个人号/商业号申报，供首次上线选择正确客户端指纹；账号 ONLINE 后由 Web 或 Android 协议执行一次轻量账号类型校验，并把可靠结果回传 Armada，用于确认或纠正当前有效账号类型。

## 已确认事实

- `account.account_type` 当前既是导入字段，也是筛选、选号和上线 `isBusiness` 的来源。
- Android 在建立连接前根据 `isBusiness` 选择个人版或商业版客户端参数，因此上线后检测不能替代导入申报。
- Web 已有账号类型检测，但事件缺少完整 Armada 业务引用，且当前 Topic 没有 Armada 消费者。
- Android 已有 `w:biz/business_profile` IQ 请求构造器，但尚未解析对应响应或回传识别事件。

## 核心模型

`account` 聚合同时保存两个不同事实：

- `declared_account_type`：导入方申报类型，仅用于保留来源事实和首次上线选择。
- `account_type`：当前有效类型；初始等于申报类型，协议可靠确认后允许自动纠正。

同时增加：

- `account_type_verify_status`：0 待校验、1 已匹配、2 已纠正、3 无法确认、4 存量未校验。
- `account_type_verify_source`：1 凭据元数据、2 配对结果、3 商业资料轻量查询。
- `account_type_verified_at`：最后一次有效检测完成时间。
- `business_verification_level`：与账号类型正交，`NULL` 未确认、1 HIGH 蓝标、2 明确非 HIGH。
- `business_verification_source` / `business_verification_verified_at`：蓝标事实的来源与水位。

这些字段属于账号身份聚合，不放入 `account_profile`：它们直接决定账号上线参数、账号筛选和业务选号，要求与 `account_type` 原子更新。现有字段不能同时保留“导入申报”与“协议确认”两个事实，因此需要新增字段。

## 导入与状态流转

1. 前端不再默认选择个人号，要求导入方明确选择“申报账号类型”。
2. 新账号同时写入 `declared_account_type` 和 `account_type`，校验状态为待校验。
3. 重复导入不得覆盖已有账号的申报、有效类型和校验状态。
4. 存量账号迁移时以当前 `account_type` 回填申报类型，状态设为存量未校验，不触发集中查询。
5. 新凭据替换时重置为待校验；同一凭据版本只自动检测一次。

## 上线命令契约

现有 `isBusiness` 保持不变，并增加：

- `detectAccountType`：仅待校验账号为 true。
- `credentialVersion`：使用 `account_credential.updated_at`，协议事件原样回传。
- `deviceOs`：1 Android、2 iOS；协议层分别映射到 Android/PLATFORM_10 与 IOS/PLATFORM_11。

协议只在 ONLINE 后异步检测，不阻塞 ONLINE 状态，不因检测失败主动断线。检测到不一致时更新数据库，下一次上线使用纠正后的指纹。

## 事件契约

统一事件名为 `account.type_detected`，写入 `protocol.account.state.events.v1` 并可靠投递。`data` 至少包含：

- `tenantId`、`accountId`、`protocolAccountId`
- `onlineAttemptId`、`commandId`
- `protocolBackend`、`credentialVersion`
- `declaredAccountType`、`detectedAccountType`、`verificationLevel`
- `source`、`detectedAt`

识别值为 `PERSONAL`、`BUSINESS_STANDARD`、`BUSINESS_VERIFIED`、`UNKNOWN`。

Armada 消费时校验租户、账号、协议账号 ID 和凭据版本。`UNKNOWN` 只更新为无法确认，不修改有效类型；可靠结果一致时标记已匹配，不一致时更新 `account_type` 并标记已纠正。重复事件通过凭据版本和检测时间条件更新保持幂等。

## 协议判定边界

- 明确商业凭据元数据或返回商业 `profile`：商业号。
- 只有成功收到可识别的非商业响应才可判定个人号。
- 超时、IQ error、解析失败和未知结构：`UNKNOWN`，不得降级判定为个人号。
- Android 的 `v=116` 响应结构需先用已知个人号和商业号各验证一次，再开启生产检测开关。
- Android 开关为 `[kafka].accounttypedetectionenabled`，默认 `false`；只在指定测试节点开启，不影响 Web 检测。

## 前端

- 导入字段改名为“申报账号类型”，取消个人号默认值。
- 账号列表继续用有效 `account_type`，增加未校验、已确认、已纠正、无法确认标签。
- 列表接口增加申报类型、校验状态和校验时间；校验来源可在详情中展示。

## 租户、部署与回滚

- 消费者始终使用事件内 `tenantId` 恢复租户上下文，查询条件同时校验 `accountId` 与 `protocolAccountId`。
- 发布顺序：Flyway/Armada 消费端 → Web/Android 生产端 → Armada 检测开关 → 前端。
- 存量账号保持存量未校验，后续单独灰度，不在本次上线制造查询洪峰。
- 紧急回滚先关闭 Android `accounttypedetectionenabled` 并停止协议生产端；新增列保留不删，旧版本仍继续读取 `account_type`。

## 未确认项与验证

- Android `business_profile v=116` 对个人号的成功响应形状：用已知个人号抓取仅标签和分类结果的脱敏日志确认。
- Android 商业号类型统一回传 `BUSINESS_STANDARD`；蓝标通过独立 `verificationLevel` 回传，只有明确 `verified_level=high` 才是 HIGH。
- Web 的 Baileys 高层接口当前不暴露 `verified_level`，因此即便存在 `verifiedName` 也只能返回 UNKNOWN，不能标蓝。
- iOS 个人/商业先作为测试环境实验能力开放；实号验证失败时调整集中维护的版本/机型画像，不影响 Android 路径。
- Web 原有凭据元数据在六段、全参和 JSON 凭据中的覆盖率：不影响正确性，缺少可靠元数据时走一次轻量查询。
