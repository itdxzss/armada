# 变更记录：进群失败分类与回执文案

- 日期：2026-09-18
- 工作区：三个项目的主仓库，保留已有未提交修改；未创建发布 worktree。
- 需求来源：进群任务把群封禁误报为邀请链接失效，用户授权在主仓库调整。
- 状态：本地实现及针对性回归完成；Android 全量测试存在下述失败，未提交、未部署。

## 设计与范围

- Web、Android 异步进群错误分类优先识别明确的群封禁和满员信息。
- 新增统一原因 GROUP_BANNED / GROUP_FULL，进群详情分别显示“群组已封禁”/“群人数已满”。
- GROUP_UNAVAILABLE 显示“群组不可用”；单独的 410 不足以认定链接失效。
- 明确 invite revoked / expired 仍为 INVITE_REVOKED；无效邀请码仍为 INVITE_INVALID。
- 后端 Android HTTP 防腐层同步分类；普通拉群共用进群链路接受新增终态原因，避免落入结果待确认。
- 共用封禁判断不再把 GROUP_UNAVAILABLE 直接当作封禁。
- 前端沿用 reasonLabel 展示，无需改页面。数据库、Redis、API 字段结构均不变，只增加原因码枚举。

## 边界

- 本次基于当前调用返回的明确错误信息分类，不根据相同邀请链接推断群封禁。
- 独立异步群健康事件与历史失败明细的群 ID、时间关联回溯尚未实现；不批量改写历史记录。
- 未读取第二套环境该任务的原始回执，不能保证截图中的旧记录自动变成“群组已封禁”，也不证明进群操作导致封禁。
- 保留原有成功进群事实，不把后续管理员操作失败改写成进群失败。

## 验证

- 先红：Web 新测试复现 9 个失败；Android 新表驱动测试复现 410 误判；Java 文案、Android 映射、共用封禁判断分别复现失败。
- Web：73 个相关测试通过，TypeScript build 通过。
- Android：gofmt、go vet ./...、go build ./... 通过；go test ./... 中 internal/armada 通过，未改动的 pkg/noise 有 8 个失败，包含缺少 vectors.txt。
- Java：JDK 17 + 显式 Byte Buddy agent，9 个相关测试类最终共 87 个测试通过（0 失败、0 错误、0 跳过），包括真实 H2 的 PullTaskStandardExecutionLifecycleServiceTest 共 24 个用例。
- Java 执行：`mvn -DargLine=-javaagent:<本机 byte-buddy-agent-1.14.19.jar> -Dtest=JoinTaskFailureReasonTest,AndroidGroupJoinErrorMapperTest,AndroidGroupJoinResponseMapperTest,JoinTaskResultServiceTest,GroupPullRetryPolicyTest,PullTaskManagerJoinOutcomeTest,PullTaskManagerJoinProcessorTest,PullTaskManagerJoinResultServiceImplTest,PullTaskStandardExecutionLifecycleServiceTest test`；修正新增测试的 completionService 参数断言后单独复跑对应类通过，新增 GROUP_FULL 的 H2 回归后单独复跑生命周期类通过。
- 三仓 `git diff --check` 通过。三个主仓当前分支均为 `1.0.3-snapshot`。
- 回滚：仅撤销本记录对应的分类、枚举及测试增量，不覆盖同文件已有在途修改；无数据回滚。

## 发布

未 commit / push / 部署。上线需要同时发布后端与对应协议层，再用原始回执及页面做业务验收。
