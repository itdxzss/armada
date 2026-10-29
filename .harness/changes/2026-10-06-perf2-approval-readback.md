# perf2 群审批关闭状态读回修复

- 日期：2026-10-06；Android 分支 `1.0.3-snapshot`，基线 `8d05bb1`。
- 用户授权：修复群资料核验卡点并部署第二套环境 perf2。
- 范围：Android 协议 IQ 群快照解析及两处测试；后端、前端、Web 协议无业务代码改动。
- 状态：已于北京时间 2026-10-06 23:03 左右完成 perf2 发布；原群实时读回受账号离线限制。

## 现场证据

任务 65 / 执行 305 在群资料动作首次返回 SUCCESS 后暂停。原群名和简介 SHA256 与冻结配置一致，`MemberAddMode=all_member_add`，但 `GroupJoinState` 为空，被后端转换为未知，无法满足简化新群要求的审批关闭条件。创建者注销仍处于 RESERVED，拉人调用为 0。

## 修复

完整群快照缺少 `membership_approval_mode` 节点表示审批关闭。此前单群和批量群解析均初始化为空，导致关闭状态无法被确认。现初始化为 `off`；一旦审批节点存在，先重置为未知，再读取显式 `group_join.state`，避免畸形节点被当作关闭。显式 `on` / `off` 保留原值，非法值仍由后端保持未知。

语义参考当前 Baileys 群快照提取的节点存在性处理，并保留 Android 显式状态优先。后端的真实群名、简介、成员权限、命令身份与租约校验保持原逻辑，不直接放行 HTTP 空字段。

无 DB、API 形状、Redis、配置、锁或重试机制变更。

## 验证与评审

- 新增/更新解析与 HTTP 契约回归先失败，现场空值被复现；修复后相关包通过。
- `gofmt`、`go vet ./...`、`go build ./...`、`git diff --check` 通过。
- `go test ./...`：仅 `pkg/noise` 8 项既有失败；从未修改 HEAD 导出 `go.mod/go.sum/pkg/noise` 单独执行，复现相同 8 项失败。其余包通过。
- 测试覆盖单群/批量缺省关闭、显式 on/off、缺少 group_join、空/非法 state，以及真实 IQ 解析到 HTTP JSON 的输出。
- 按 expert-reviewer 维度检查完整 diff、HTTP 映射和后端消费者：无新的状态绕过、身份变化或存储写入；不存在阻断项。发布后原建群号离线，尚未取得新的真实群审批读回。
- 部署脚本语法和 `deploy-test.test.sh` 通过。生产离线打包测试因既有缺失 `prod/scripts/inspect-production-host.sh` 失败；本次未使用生产离线包。

## 发布与回滚

- 已核对 perf2 原有 login/vo/executor 修复的远端源码 SHA256 与本地一致，避免覆盖已有修复。
- 发布命令：`bash armada-deploy/deploy-test.sh --env perf2 --zhuan --yes`；仅同步本次 3 个文件。
- 回滚镜像：`whatsapp-server-feature-android-zhuan:before-approval-state-20261006-305`。
- 远端源码备份：`deploy/rollback/approval-state-20261006-305/source-before.tar`，仅包含本次三个源码/测试文件。
- 本次未提交或推送 Git，未恢复/结束任务或触发拉人、注销。
- 过程日志：`/private/tmp/perf2-execution305-20261006/`，不包含账号凭据。

## 部署后结果

- 部署脚本退出码 0，Zhuan SUCCESS，后端/前端/Web 协议 SKIPPED。仅 3 个文件同步，Zhuan 阶段用时 137 秒。
- 运行镜像 `sha256:e2cdde574cb5bea7d9a66e67707a2660493e45b1e3737865d3e19fe8b74a9436`；容器启动 `2026-10-06T15:01:55.443321282Z`，restartCount=0，容器/API 就绪。
- `/proc/1/exe` 和 `/app/whatsapp-server` SHA256 一致：`8428ec27febd6053f0e9f357422fdd87b8e3d09d8735c4cf9f312e8f44170183`；三个修复文件远端与本地哈希一致。
- 23:03:26 原建群号 2757 的只读状态查询和原群查询返回 Code=1003（账号当前不在线）。因此不能宣称原群已实测返回 off，也未触发重登录、拉人、营销或注销。
- 23:03:36 查询原执行 305 已是 execution_status=6（已放弃），manual_paused=false；更新时间为 22:53:02.450，早于本次发布。该终态不是本次操作造成；不能恢复此执行。拉人调用仍为 0。
- 后续业务需要重新上线使用的账号并发起新任务；新群真实读回和业务结果仍须现场验证。
