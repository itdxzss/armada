# 变更记录：群成员 Web / Android 真实踢出

- 日期 / 分支 / worktree：2026-08-09 / `1.0.2-snapshot` / `D:\idea_project\armada`
- 需求来源：群组列表的群组明细需要支持 Web、Android 协议真实踢出成员
- 状态：已完成本地实现与自动化验证；待测试环境真群验收

## 目标

群组明细发起踢出后，Web 与 Android 都调用真实 WhatsApp 成员移除能力；只有同一执行账号回读确认目标成员已经不在群内时才向页面返回成功。

## 范围

- Web 保留现有 Baileys `participants/remove` 调用，补齐成功后的 WhatsApp metadata 回读确认。
- Android 接入 Zhuan 已存在的 `POST /ws/v1/groups/members/remove/{wsPhone}`。
- 保留现有 `POST /api/group-links/{id}/members/kick-batch` 和前端逐项结果契约。
- 不修改数据库结构，不引入重试换号，不改升降管理员和其它群设置行为。

## 验收标准

### AC-001：Web 真实踢出并拒绝假成功

- 场景：Web 执行账号是群管理员，目标是非群主成员。
- 动作：调用群组明细踢出。
- 预期：Baileys 返回 `OK` 后仍使用原执行账号回读 metadata；目标成员不存在才返回 `OK`。
- 禁止：仅凭 HTTP 2xx 或逐成员 `200` 返回成功。
- 验证：`GroupDetailServiceImplTest` 覆盖“已移出”和“仍在群内”两种回读结果。

### AC-002：Android 调用真实成员移除接口

- 场景：Android 执行账号在线且是群管理员。
- 动作：调用群组明细踢出一个或多个非群主成员。
- 预期：逐成员调用 Zhuan remove endpoint，请求携带 `group_id` 和 `participant`；`Code=0` 进入回读确认，失败项保留明确结果。
- 禁止：返回 `GROUP_CAPABILITY_UNSUPPORTED` 或只更新本地快照。
- 验证：`AndroidNativeGroupParticipantAdapterTest` 与 `HttpAndroidNativeClientTest`。

### AC-003：协议失败和部分成功不被折叠

- 场景：批量目标中存在权限失败、超时或回读仍存在的成员。
- 动作：执行批量踢出。
- 预期：逐 JID 返回 `OK`、`FAILED` 或 `UNKNOWN`；只有全部成员经回读确认移出时 `ok=true`。
- 禁止：换另一个账号重复执行，或把未确认项提示为成功。
- 验证：群详情 service 回归测试和 Android adapter 部分失败测试。

## 影响

- 数据库变更：无。
- API 变更：Armada 对外路径和 JSON 结构不变；Android 原生 client 增加内部 remove 能力。
- Redis 变更：无。
- 回滚：回退本变更涉及的 Android client/adapter 与群详情回读确认代码；无数据回滚。

## 任务清单

- [x] 核实 Web、Android 当前调用链与真实 Android endpoint。
- [x] 补失败复现测试并确认红灯。
- [x] 实现 Android remove 适配与 Web/Android 统一回读确认。
- [x] 运行定向测试、编译和 diff 审查。

## 验证

红灯证据：

```powershell
cd D:\idea_project\armada\armada-api
mvn -q -DforkCount=0 "-Dtest=GroupDetailServiceImplTest,HttpAndroidNativeClientTest,AndroidNativeGroupParticipantAdapterTest" test
```

- 首次失败：`AndroidNativeClient` 缺少 `removeGroupMember`，测试编译失败。
- 接入 Android endpoint 后仍失败 2 项：Web 协议 `OK` 后 metadata 只读取 1 次；成员仍在群内时错误返回 `ok=true`。

绿灯证据：

```powershell
cd D:\idea_project\armada\armada-api
mvn -q -DforkCount=0 "-Dtest=GroupDetailServiceImplTest,GroupLinkControllerTest,HttpGroupParticipantAdapterTest,HttpAndroidNativeClientTest,AndroidNativeGroupParticipantAdapterTest,AndroidGroupOperationErrorMapperTest,ProtocolConfigurationTest" test
```

结果：7 个测试类共 55 个测试，`Failures: 0`、`Errors: 0`、`Skipped: 0`。

```powershell
mvn -q "-Dmaven.test.skip=true" compile
```

结果：退出码 0。

## 待测试环境验收

- 选择明确授权的测试群与非群主成员，分别使用 Web、Android 管理员账号执行踢出。
- 页面返回 `OK` 后，在 WhatsApp 客户端刷新群成员，确认目标成员已不在群内。
- 使用非管理员账号验证权限失败，确认不会换号重试或显示成功。
- 未经目标环境确认，不执行远程或生产真群操作。
