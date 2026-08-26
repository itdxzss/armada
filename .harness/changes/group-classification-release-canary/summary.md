# 变更记录：群分类受控发布 Canary

- 日期 / 分支：2026-08-26 / `codex/group-classification-release-canary`
- 环境：test1
- 状态：本地已验证，待 Runner 远端执行

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

## test1 结果

待 Runner run 完成后补充 run ID、终态、checksums、群分类结果和版本核对。
