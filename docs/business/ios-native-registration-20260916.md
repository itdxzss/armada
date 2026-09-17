# iOS 个人版单号注册

## 已确认范围

用户要求 IPA 使用现有接码接口完成注册，终点为原生注册成功。没有导出、导入、退出、协议上线或账号池步骤。

## 第一版链路

1. 管理员在服务端配置一个短期、单次注册许可，绑定租户、设备标识、请求 UUID、美国渠道、单价和可选的指定商家。通用包的令牌在运行环境与手机 Keychain；用户授权的私有预配置包另含本次专用配置。
2. 手机读取许可报价，用户开启注册开关并点击开始。相同许可永远返回同一个任务；失败也不重新买号。
3. 复用已有注册 worker、Grizzly 采购核价和未知结果处理。新任务执行模式为 `IOS_DEVICE`，固定数量 1、个人类型 1。
4. IPA 在原生注册页面填入号码，使用原生流程申请验证码。手机轮询服务端，验证码只在内存响应中传递。
5. IPA 只在当前任务号码对应的原生注册成功回调中回报成功。服务端状态 `DEVICE_REGISTERED` 表示设备报告注册成功，不表示协议在线。此处结束。

验证码、2FA、额外验证及冷却限制遵循宿主行为；遇到不支持的界面暂停，禁止绕过或循环发码。首版需要前台运行。中断只恢复同一任务。

## 持久化与复用

继续使用 `account_registration_task/item` 注册聚合，不另建采购表。任务新增 `execution_mode` 区分既有 Cobalt 与 iOS；`device_id` 绑定设备；`purchase_before` 保存采购许可截止时间，防止排队后越过授权窗口。三者均用于执行校验。允许 group/IP 列为空，因为手机任务不执行导入或协议上线。

既有管理端列表只展示 Cobalt 任务，避免将手机注册报告当作上线成功。手机接口只能查令牌绑定的请求 UUID，不能枚举其他订单。独立注册令牌不继承导入令牌权限。

## API

所有请求：HTTPS，`X-Registration-Token`，`X-Device-ID`，POST JSON。路径 `/api/device-registrations/{status,start,result,options}`，响应使用 `{code,message,data}` 并禁止缓存。

- `status` 与 `options` 请求为 `{}`。options 返回 `requestId,countryId,unitPrice,providerIds`，仅包含本次许可同一国家和价格的实时商家。
- r7 的 `start` 必须为 `{requestId,providerId}`；providerId 为一个有效商家码或空字符串（按当前价档选择）。必须匹配手机确认的请求 ID；过时请求拒绝。创建前和采购前分别复核报价，已开始任务不允许更改商家。
- `result` 为 `{requestId,phoneNumber,outcome}`，outcome 为 `REGISTERED` 或 `STOPPED`。不接受日志、原始错误或凭据。

`ARMADA_DEVICE_REGISTRATION_CLIENTS_JSON` 默认空数组。**r7 每项仅包含 `token,tenantId,deviceId`**，升级时需要一次性移除旧配置中的采购参数。令牌绑定设备与租户，接码平台密钥只在服务端。单次许可由 V198 表 `account_registration_device_permit` 管理；V197 的任务 provider_id 记录实际选择，历史任务不变。

控端 `/api/account-registrations/devices` 需 `tenant:account:edit` 权限：POST 保存许可（设备、请求 UUID、国家、单价、默认商家、有效期，最长 24 小时），GET `/{deviceId}` 只读当前许可与任务，POST `/{deviceId}/start` 显式确认单号采购。GET 不轮询短信、不返回验证码。保存许可不会产生采购；页面的独立确认按钮才启动同一 IOS_DEVICE 流程，取号后由手机继续。该流程不调用 Cobalt，不导入账号。

许可替换和开始任务共用同一设备许可行锁。只有旧许可未开始，或旧任务明确 FAILED/CANCELLED 且没有号码和订单，才能发放新许可。UNKNOWN、已有号码和执行中的任务禁止替换。新任务保持单明细，同请求幂等恢复，商家不可变。手机仅在手动确认时采用服务端发放的替代许可，核对 replacesRequestId 并保护本地号码和提交标记。控端已取号的同一任务可由手机明确接续，不重复采购。

`armada.account.registration.device-enabled` 默认 false；还需已有注册/调度/Grizzly 采购开关。手机流程不依赖 Cobalt 能力。实时报价消失时停止，不自动换价、不自动补购。成交价不符则走已有订单取消流程。

## 验收与交付边界

### 第一套 test1 配置（2026-09-16）

- 已部署后端与 V196；既有 HTTPS 设备网关新增三个精确注册 POST 路径，透传 `X-Registration-Token` 和 `X-Device-ID`，请求体上限 2 KiB，禁止缓存和管理身份透传。
- 手机服务地址为 `https://ingest.65.2.123.53.nip.io`，不带 `/api` 路径。通用包通过本机受保护且被 Git 忽略的文件交付注册令牌。用户于 2026-09-16 明确要求免配置，r5 私有测试包可预置本次固定单号许可；首次启动保存至 Keychain，已有任务不得覆盖。该包共享同一笔许可，不是硬件认证或通用分发包。令牌不写入源码、文档、manifest 或对话，接码平台密钥始终仅在服务端。
- 本次单许可绑定用户截图中的设备，使用美国普通渠道 187、价格 0.88（供应商计价，未假定币种），有效期 24 小时。配置及验证只查询 status，实际采购必须由手机确认触发。
- 初次配置的公网 HTTPS 正确设备查询得到 `NOT_STARTED`，错误设备被拒绝；后端稳定、迁移成功、运行制品一致。
- 14:43 用户在 r5 手机端确认后创建任务 5 / 明细 15；采购返回 `SMS_NO_NUMBERS`，无手机号或 activation，原任务保留。随后用户要求按 Chrome 历史记录的商家 222 再从手机试一次；r6 新许可的准备、部署与验证记录见 `.harness/changes/ios-native-registration/provider-222-retry.md`。
- 15:27 r6 后端及 V197 已部署并通过运行 JAR、公网 HTTPS 验证；新许可固定商家 222、国家 187、价格 0.88、数量 1，状态 NOT_STARTED。旧包空 start 和过时请求均被拒绝；未发起有效 start。r6 未签名预配置 IPA 已交付，待用户签名更新并在手机确认本次开始。

本地验证覆盖真实 Mapper XML/H2/租户插件、同键重放、设备绑定、未知采购不重试和成功不调用导入。IPA 固定个人 26.36.74/build 1067315676/主程序 UUID，有精确类型和类保护。

离线测试、编译和生成未签名 IPA 不等于真机注册成功。部署前确认环境；真实试验单独确认渠道、单价和一个号码的预算。不得覆盖用户当前已注册的应用或退出其账号。
