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
