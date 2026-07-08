# 变更记录：建群营销任务导出

- 日期 / 分支 / worktree: 2026-07-07 / 1.0.1-snapshot / 当前工作区
- 需求来源: 用户要求在建群营销任务列表支持多选导出统计表
- 状态: 进行中

## 目标（一句话）

在建群营销任务列表按选中任务导出 xlsx，包含任务 ID、群名称、建群人数、进群人数和合计。

## 缺口拆解 / 任务清单
- [x] 建群营销明细表增加发送前群人数快照字段
- [x] worker 发送营销消息前查询群成员并保存快照
- [x] 后端提供选中任务导出 xlsx 附件接口
- [x] 前端列表增加选择列和导出按钮
- [ ] 真库 DbTest 验证

## 关键设计决策
导出基于 `group_creation_marketing_item` 明细行展开，建群人数用 `participant_count + 1`，进群人数用发送前保存的 `send_member_count - 1`。群人数查询失败不阻断营销发送，导出时该明细进群人数留空。

## 验证（evidence-before-done）
```bash
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home PATH=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin mvn -DskipTests test
```
结果：BUILD SUCCESS。

真库/Mockito 测试当前受本地执行沙箱限制：MySQL socket 连接报 `Operation not permitted`，Mockito inline mock maker 报无法 self-attach。

## 部署
- commit / 环境 / 部署后验证结果: 未提交，待用户查看 diff。

## 遗留 / 跟进
- 在允许连接测试库和 JVM attach 的本机环境中运行 focused DbTest/单测。
