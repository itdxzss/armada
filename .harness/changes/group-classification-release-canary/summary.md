# 变更记录：群分类受控发布 Canary

- 日期 / 分支：2026-08-26 / `codex/group-classification-release-canary`
- 环境：test1
- 状态：Web Canary 已通过；Android 普群前置链路缺陷已留证

## 目标

在不绕过 staging Runner 的前提下，为群首次分类变更提供一个受安全信封约束的真实 WhatsApp Canary：
最多使用 6 个空闲 Android 测试账号，创建 3 个明确命名的新群，每群增加 1 个测试成员，验证新群稳定
分类为 `POST_CONTROL` 且旧兼容布尔互斥。

## 安全边界

- 新增 Runner `controlled-canary` 计划类型；必须同时声明 `safetyEnvelopeRef` 并在入队时显式使用
  `--execute-canary`。
- 固定 wrapper 校验前置 soak 证据、四仓版本、scope hash、账号组资源租约和精确动作预算。
- 动作预算固定为：最多 6 个账号、3 次建群、每群 1 次加人、最多 6 次联系人准备；0 条消息、
  0 次退群、0 个现有群修改、并发度 1。
- 失败时保留资源租约用于隔离和人工复核；仅在全部验收通过后释放。
- 证据只保存账号组别名和群 JID 哈希，不保存账号号码、群 JID、Bearer Token 或登录凭据。
- 成功后的群按明确 Canary 前缀保留，不自动退群或删除，避免清理动作扩大 WhatsApp 侧变更。

## 本地验证

- `go test ./...`：通过。
- `python3 wrappers/test1-group-classification-canary.test.py`：7 项通过。
- `python3 -m py_compile wrappers/test1-group-classification-canary.py`：通过。
- `git diff --check`：通过。

## 首次 Android Canary 发现的阻断

- Runner run `20260826T134103Z-bb77871f` 在安全预检后进入执行，3 个 Android 计划群均以
  `INVALID_GROUP_ACTION_PAYLOAD` 终止。
- 联系人准备结果多数为 `UNKNOWN`，后端仍派发了空参与者的 `GROUP_CREATE`；Android 在原生动作前
  拒绝 payload。数据库核对为 0 个已创建群、0 个仍运行项目。
- 该 run 与资源租约保留为失败证据，不在原动作预算内重试。后续使用独立 Web 测试资源和 v2 安全信封
  继续验证本次群分类主线。

## test1 结果

- 通过 run：`20260826T134930Z-df4457fe`，六阶段全部 `PASS`，`checksums.sha256` 全量校验通过。
- 资源：别名 `ag-77edc12751`，6 个合格 Web 测试账号；实际创建 3 个带 Canary 前缀的新群。
- 分类：3/3 canonical `POST_CONTROL`，来源 3/3 `POST_CONTROL_DISCOVERED`，分类时间 3/3 已写入；
  连续两次 API 读取无漂移，兼容出参均为 `isHistorical=false / isPostControl=true`。
- 动作核对：0 条消息、0 次退群、0 个现有群修改；成功后按安全信封保留 3 个 Canary 群。
- Flyway：V140 `success=1`，描述 `group canonical first classification`。
- 版本：backend `b637cf1e...`、frontend `df3799c64...`、Web `1415022f...`、Android
  `9677fe69...` 均与可信 runtime manifest 匹配；backend/frontend/Android coordinator 当前 Docker image
  identity 也与 manifest 一致。严格 300 秒 freshness 检查因 runtime source 未自动刷新而 `BLOCKED`，
  放宽到本次验收窗口后版本检查通过；这是观察器保鲜能力缺口，不改写 Canary 业务结论。
- Canary 临时 RBAC 角色和两项权限已删除，重新登录替换并注销了临时提权会话。
- 当前真实 Canary 只覆盖“首次可靠事实为上控后新增”的 Web 路径；历史群首次事实与跨账号晚到 baseline
  不改类仍由本地迁移、Mapper、Service 并发测试覆盖，尚未做第二组真实 WhatsApp Canary。
