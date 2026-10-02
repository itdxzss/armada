# whatsapp-sdk 新号注册接入分析

日期：2026-09-15。范围：公开上游及本地主仓静态分析；没有采购号码、发送验证码、连接业务远程环境、安装执行 SDK 或修改业务代码。

## 结论

架构可接入，但当前公开交付物不足以直接实施或验证注册。先取得可运行完整 SDK，验证一个受控号码的注册、凭据导出及现有 Zhuan 首次登录，再决定正式集成。SDK 宣称注册成功不能替代 Armada 的真实上线验收。

## 上游事实

- [README](https://github.com/whatsapp-sdk/whatsapp-sdk)：声明 Android 注册，示例为 `sms()`、`register({code})`、`login()`；标注更新时间 2025-06-24，并提及源码销售和维护服务。这些是作者声明，不能证明当前注册有效。
- GitHub API 本次返回 main 树 SHA `957a845d1e310e9ecf9efb8c152f0373c511a3bc`。
- [src/index.js](https://github.com/whatsapp-sdk/whatsapp-sdk/blob/957a845d1e310e9ecf9efb8c152f0373c511a3bc/src/index.js) 和 `src/lib/libsignal/index.js` 均为 0 字节；公开树包含协议编码/解码、protobuf 等，但缺少可用 SDK 入口。
- [package.json](https://github.com/whatsapp-sdk/whatsapp-sdk/blob/957a845d1e310e9ecf9efb8c152f0373c511a3bc/package.json) 的包名为 `whatsapp-sdk`，main 为根目录 `index.js`，公开树没有该文件；README 的安装名却是 `whatsappsdk`。
- 本次只读访问 [README 指定的 npm 包](https://registry.npmjs.org/whatsappsdk) 返回 HTTP 404，`/latest` 返回 `Not Found`。仅说明当前公开发布入口不可用，不证明作者没有私有交付物。
- 未取得完整注册实现，因此代理参数、会话恢复、返回字段、完整密钥导出及当前版本兼容性均未确认。

## Armada 当前代码

本地主仓分支 `1.0.3-snapshot`，分析开始 HEAD `3493794f`。

1. 前端 `wheel-saas-pure-web/src/api/account-registration.ts` 使用 `/api/account-registrations`，入参没有注册引擎选择字段。
2. `AccountRegistrationServiceImpl` 提供目录、报价、创建和任务查询；当前只筛美国目录。创建及采购前检查业务开关、scheduler、Grizzly 和 Cobalt health/capacity。
3. `AccountRegistrationWorker` 负责 Grizzly 采购、固定注册 ID、查询 Cobalt、提交接收到的 OTP、导出凭据、导入与等待真实在线。
4. `AccountRegistrationImportService` 要求 `zhuan-six-v1`，复用账号导入和分组，进入 `WAITING_ONLINE`；worker 收到账号在线事实后才标记成功。
5. 现有独立项目 `cobalt-registration-service` 已有 `RegistrationEngine` 与内部 HTTP 服务。该接口是 Java 的 register/exportSix，不能直接装入 Node.js SDK；跨进程仍需桥接或实现同一 HTTP 合同。
6. 当前 Cobalt 项目 README 记录实号验证未完成注册。该信息来自本地文档，本次未复核远程状态。

现有主链：新号注册页面 → Armada 任务 → Grizzly 购号/接码 → Cobalt 注册 → 六段导入 → Zhuan 登录 → 在线计成功。

## 建议接入方式

新增独立 Node.js 注册适配服务，复用现有内部合同；在 Armada 侧把 Cobalt 专属客户端依赖抽为注册客户端接口，实现按引擎路由。Grizzly 继续提供号码及 OTP，SDK 负责向 WhatsApp 请求和提交验证码。

| 内部合同 | SDK 适配职责 |
| --- | --- |
| GET /v1/health | 如实返回注册开关、依赖就绪和可接收容量 |
| POST /v1/registrations | 按稳定 ID 建立并持久化会话，发起 sms；重复调用不得重建设备或重发 |
| GET /v1/registrations/{id} | 查询持久化状态，明确区分等待、注册成功、失败与结果未知 |
| POST /v1/registrations/{id}/code | 将 OTP 提交到原会话，处理重复请求和响应丢失 |
| POST /v1/registrations/{id}/credentials | 仅注册确认后导出兼容凭据 |

这张表是目标适配合同，不是已经核实存在的 SDK HTTP API。

### 核心缺口

- **凭据兼容**：当前 Zhuan 顺序为 phone、static public key、static private key、identity public key、identity private key、phone ID。Java 还校验四个密钥各为 32 字节规范 Base64，phone ID 为 UUID。必须映射注册时同一套真实密钥；不能另造密钥或把任意 device ID 当 phone ID。格式通过仍需真实登录验证。
- **会话持久化**：必须跨 HTTP 请求和进程重启保持设备、密钥和注册状态；上游超时不能简单重建会话。应保留完整原生凭据用于恢复，六段只是当前导入视图。
- **错误合同**：保留规范化 reason 和必要等待信息，不能把 HTTP 200 或短信受理当注册成功；额外交互未支持时明确停止并记录待处理原因。
- **注册代理**：当前创建注册请求只有 ID、号码和 accountType；页面 IP 配置在账号导入阶段使用。若 SDK 注册需要代理，需单独明确注册阶段代理分配及会话绑定，不能认为现有页面字段已传入 SDK。
- **账号类型**：确认完整 SDK 支持个人与商业注册并正确传递 accountType。
- **引擎绑定**：多引擎共存或有在途任务时，持久化每个任务的引擎来源并用 Flyway 迁移，历史任务归 Cobalt；禁止切换全局地址使旧 ID 路由到新引擎。

## 改动范围与回滚

- 前端：若第一期后台固定引擎，页面结构可复用，仅补必要状态/错误展示；让用户选引擎才新增目录、表单和任务展示字段。
- 后端：客户端接口与实现、能力门禁、worker 类型/错误映射、凭据模型与导入来源文案；保留当前租户上下文、鉴权、幂等键及短事务边界。
- 数据：任务和明细继续复用。多引擎路线新增引擎标识并纳入幂等参数比较；OTP 与密钥不进入任务表或前端响应。
- 部署：新增 Node.js 服务及持久化卷，存储依赖以完整交付物为准；内部鉴权、会话隔离和凭据保护按既有注册服务标准执行。
- 回滚：关闭新引擎新建能力，保留在途会话并按原引擎收尾；保留已采购订单和凭据，不能简单切地址或删卷。

## 后续验证顺序

1. 获取完整交付物、明确版本、注册/代理/存储/导出文档和使用授权。
2. 离线核验入口、实际依赖、字段含义、密钥转换和重启恢复；合同测试覆盖幂等、响应未知、跨租户访问及敏感数据不外泄。
3. 确认测试环境和测试号码后，完成一次真实发码、收码、注册及完整凭据保存。
4. 使用同一注册会话导出的凭据，在现有 Zhuan 完成真实首次上线；再验收 Armada 导入、分组、任务结算闭环。

未完成第 1 步前无法可靠估算完整交付周期；HTTP 对接本身可控，真正不确定的是上游可运行性和凭据兼容性。
