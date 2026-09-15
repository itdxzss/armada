# 新号注册菜单归属

## 目标与范围

2026-09-15 用户确认：“我们的 armada 页面放在账号管理下面吧，叫新号注册”。
在主仓 `1.0.3-snapshot` 本地增加独立页面菜单，保留既有账号导入入口和注册业务接口。

## 实现与约束

- Flyway 起始最高版本为 V193，本次新增 V194 `account_registration_menu`；仅写现有 `sys_menu`，无表结构变更。
- 各租户已有 `AccountManagement` 目录下新增 `AccountRegistration`，显示名称“新号注册”，排序 40。
- 路由 `/account/registration`，组件 `account/registration/index`；同步 `MenuManagementServiceImpl` 前端组件白名单。
- 复用 `tenant:account:edit`。租户管理员动态获得菜单，普通角色沿用显式菜单授权，不新增角色授权记录。
- API、Redis、注册采购和 Cobalt 流程均无变更；相关在途实现见 `../account-sms-registration/summary.md`。

## 验证

- 新增 H2 MySQL 模式迁移测试：两个租户父目录归属、名称/路由/权限、重复执行、保留账号导入目录和原角色授权。
- 新增菜单管理测试：允许创建使用新号注册组件的菜单；租户管理员无显式角色授权时仍返回账号管理下的新号注册路由、组件和编辑权限。
- 当前 `/api/tenant/me/menus` 的后端元数据只有 `title/icon/rank/auths`，没有 `module_key` 字段；账号模块识别沿用前端路由适配逻辑。
- 首轮两例均按预期失败：V194 文件不存在、组件不在白名单。
- 聚焦命令：`cd armada-api && mvn -q -Dtest='AccountRegistrationMenuMigrationTest,MenuManagementServiceImplTest,FlywayMigrationVersionContractTest,FlywayMigrationSqlContractTest' test`。
- 最终退出码 0，17 例通过、0 失败、0 错误、0 跳过；其中迁移在 H2 MySQL 模式真实执行两次。未做共享 MySQL 或浏览器运行时验收。
- `git diff --check` 通过；仅增加菜单白名单、迁移和相关测试，没有覆盖其他注册业务在途变更。

## 回滚

通过新 Flyway 迁移停用 `AccountRegistration` 菜单，并回退本次前端入口及白名单代码；建议脚本见 `rollback.sql`。
不删除既有菜单授权或注册订单，不修改已执行迁移。

## 部署

2026-09-15 用户授权 commit/push，并确认第一套测试环境 test1；本次为页面与接口展示发布，采购与 scheduler 保持关闭，不部署 Cobalt 注册容器。

发布前修复注册任务统计查询的 `unknown` 别名引用和 primitive record 构造映射；修正一个 Mockito 重设桩调用导致的测试空指针。
Grizzly、Cobalt client、注册编排、H2 Mapper、菜单和 Flyway 共 150 项聚焦测试通过（0 失败、错误、跳过）。XML 与 diff 检查通过。
`deploy-test.test.sh` 通过；生产离线包测试因仓库缺少 `prod/scripts/inspect-production-host.sh` 失败，与 test1 本次范围无关。
部署前评审在采购关闭范围内无新阻断；启用采购前仍需补调度可用性门禁、导入阶段超时及真实链路验收。
提交、远端版本与部署产物以本次发布日志及最终验收记录为准；不把页面发布视为真实购号注册通过。

首次 test1 启动验证发现 `AccountRegistrationLease` 的 `StringRedisTemplate` 注入歧义：运行配置同时注册认证与业务 Redis。补充 `groupCreateIdempotencyRedisTemplate` 限定并新增双 Redis 装配回归测试，再单独重发后端；V193/V194 在首轮已成功执行，无须重复更改迁移。

## Grizzly 查询配置与真实目录验收

用户随后提供 API 密钥并授权配置 test1 查询。部署编排补充 `GRIZZLY_SMS_ENABLED`、`GRIZZLY_SMS_PURCHASES_ENABLED`、`GRIZZLY_SMS_API_KEY` 映射；真实密钥仅保存在服务器私有环境配置，示例文件保留空值。查询启用，采购、注册、注册调度和 Cobalt 保持关闭。

只读余额查询确认密钥有效；真实目录返回美国（187）与美国虚拟（12）两个渠道。美国 WhatsApp V2 报价返回六档，V3 供应商结构与现有解析契约一致。

页面验收发现真实服务目录中其他服务名称 `Hanwha Life` 末尾带制表符，导致整份服务目录被严格文本校验拒绝。修复范围限定为服务展示名称首尾空白规范化；服务代码、名称内部控制字符、号码、金额等字段仍沿用原校验。修复需通过针对性回归及重新发布后的页面验收，不以只读供应商查询代替 Armada 页面验证。

回归先复现真实目录失败，再完成最小修复。`GrizzlySmsClientTest`（66 项）与 `GrizzlySmsPriceTiersTest`（37 项）共 103 项通过，0 失败、错误、跳过；`git diff --check` 与部署脚本回归通过。配置及解析 diff 评审无阻断项。

## test1 注册执行链路启动（2026-09-15）

用户要求继续启动“购号 → 接码 → Cobalt 注册”。本次补齐独立 Cobalt HTTP 服务、test1 Compose 环境变量透传与注册调度开关，初始并发 1。启动前只读确认注册任务表为空。

- 接单能力检查增加 scheduler 属性及 kafka Profile 判断，防止页面接单后没有执行器。
- 凭据导出/导入阶段增加总时限，超过 30 分钟进入 UNKNOWN 保留事实，避免阻塞全局队列。
- 真实密钥只写 test1 服务器环境文件；HTTP 端口只绑定回环，通过 Armada Docker 网络和 Bearer 鉴权连接。
- 后端注册/Cobalt 相关 35 项测试通过，部署脚本测试通过；独立 Java 25 Cobalt 包 16 项离线测试通过。
- 发布前评审：上述调度缺失和无限等待问题已修复，无新增 SQL/迁移；未知采购结果不重购、固定注册 ID 幂等、真实 ONLINE 回调才成功的边界保持。真实 WhatsApp 注册及六段导入上线仍须通过实际任务验收，健康检查不能替代。

### 启动验收结果

- test1 后端执行版本 `1695e7aa` 已推送并部署，运行 Jar SHA-256 `2afec26092771f8fee8b65396aeedbeb83cb32761b2f3cc52c9ff6729f1c6499` 与固定发布 worktree 一致。
- Cobalt 镜像 `cobalt-registration:cb9fbf1-http-20260915-28d48541`，运行 Jar SHA-256 `28d48541014691e1e4a0212cb9be9fceea3473a53c94cb6daebf76db50091889`；健康状态 UP、注册开启、可用槽位 1，未授权健康请求返回 401，宿主端口仅回环。
- Grizzly 集成/采购、Armada 注册/调度、Cobalt 客户端五项开关在运行容器中均为 true；两容器运行且重启计数 0，启动后未观察到注册调度错误。
- 登录浏览器打开 test1 新号注册页面，`REGISTRATION_DISABLED` 提示消失，`提交采购与注册` 按钮可用；浏览器会话已结束。
- 本次没有提交付费采购任务。端到端实号注册、凭据导入及首次上线仍待用户选择实际档位/数量后验收。
