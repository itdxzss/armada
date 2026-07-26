# Flyway 历史迁移对齐设计

## 目标

让 `1.0.2-snapshot` 在不修改第一套测试库 `flyway_schema_history`、不执行
`flyway repair` 的前提下，与数据库已经执行的 V061-V075 完全兼容，并把尚未执行的
“账号期望登录状态”迁移安排到新的 V076。

## 已确认事实

第一套测试库和当前可运行旧 JAR 已经通过 Flyway 校验，现有历史如下：

| 版本 | 脚本语义 | 数据库校验和 |
| --- | --- | ---: |
| V061 | promotion template channel statistics | 1676422917 |
| V062 | promotion channel country values | 232278498 |
| V063 | promotion template visibility and seed | 1573867056 |
| V064 | promotion template single domain | -611435249 |
| V065 | promotion domain soft delete uniqueness | 1198524220 |
| V066 | promotion channel runtime config | 748421947 |
| V067 | promotion pairing account phone index | -1457194837 |
| V068 | promotion pairing IP reservation | 1122157768 |
| V069 | promotion pairing session | -1009231184 |
| V070 | group pull marketing | -168361012 |
| V071 | system management RBAC | -315144987 |
| V072 | default tenant admin user | 166505662 |
| V073 | default admin password | -1156189687 |
| V074 | default admin password policy | -1863275304 |
| V075 | restore task center menu structure | 2104574531 |

当前 `1.0.2-snapshot` 只包含：

- `V061__group_pull_marketing.sql`，其 Flyway 校验和为 `-168361012`，实际对应数据库 V070；
- `V062__account_desired_login_state.sql`，其 Flyway 校验和为 `-1202454221`，数据库尚未执行。

因此问题不是 SQL 内容本身，而是分支合并后丢失了 V061-V075 的既有历史，并复用了已执行版本号。

## 方案比较

### 方案一：代码侧恢复真实迁移历史（采用）

从第一套环境当前可正常启动的旧 JAR 中按原始字节恢复 V061-V075；用其已经通过
Flyway 校验的内容作为历史事实。将账号期望登录状态迁移保持内容不变，仅改名为 V076。

优点：不篡改数据库历史；所有已执行校验和保持一致；新环境也能按完整顺序建库。
代价：需要恢复 15 个历史脚本，并增加契约测试防止再次丢失或改写。

### 方案二：执行 `flyway repair`（否决）

把数据库 V061/V062 校验和改成当前代码值。这样会把已经执行的推广迁移伪装成拉群和账号状态迁移，
同时遗漏 V063-V075，数据库事实与代码事实永久分叉。

### 方案三：关闭 Flyway 校验（否决）

只能绕过启动保护，无法补齐缺失迁移，也会让后续环境继续积累冲突。

## 代码与数据设计

1. `db/migration` 中恢复旧 JAR 的 V061-V075，文件名和内容逐字节保持一致。
2. 删除当前错误编号的 `V061__group_pull_marketing.sql` 和
   `V062__account_desired_login_state.sql`：
   - 拉群营销由恢复后的 `V070__group_pull_marketing.sql` 承载；
   - 账号期望登录状态改为 `V076__account_desired_login_state.sql`，SQL 内容不变。
3. 不新增手工 DDL，不修改 `flyway_schema_history`。部署时 V061-V075 只做校验，只有 V076
   作为待执行迁移运行；V076 已带 `information_schema` 守卫，列存在时也不会重复添加。
4. 更新拉群营销迁移测试路径为 V070。
5. 恢复 Flyway 全局版本唯一性测试，并增加 V061-V076 文件名、描述和校验和契约测试。
6. 修正测试部署脚本的健康判定，接受当前未登录响应业务码 `40104`，避免应用已正常启动时脚本误报失败。

## 完整性门禁

本次不能只验证“没有重复版本”，必须同时满足：

1. V061-V076 每个版本恰好一个文件，文件名与预期映射一致；
2. V061-V075 的 Flyway 校验和与第一套测试库逐项一致；
3. V070 保持拉群营销校验和 `-168361012`；
4. V076 保持账号期望登录状态校验和 `-1202454221`；
5. Maven 打包后的 JAR 确实包含完整 V061-V076，而不是只检查源码目录；
6. 部署前再次只读查询第一套测试库历史，部署后确认容器不重启、日志无 Flyway 错误、接口返回预期未登录响应。

## 回滚

- 代码回滚：恢复部署前旧后端制品；旧代码会忽略 V076 新增的可空列。
- 数据回滚：默认不删除 `desired_login_state`，避免破坏已写入的控制面意图。只有明确要求彻底回退时，
  才通过新的后续 Flyway 迁移处理，禁止手工删除历史记录或修改校验和。

## 范围

只修改 Armada 后端迁移、相应测试和部署健康判断；不修改前端、协议层、营销菜单/API，
也不改变本轮行锁收敛业务逻辑。
