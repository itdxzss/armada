# 变更记录：perf2 Android 热修复正式提交与发布

- 日期：2026-10-09（Asia/Shanghai）
- 需求来源：用户明确指定将 Android 主仓库 8 个未提交业务文件分两次提交、推送，并仅部署第二套环境 perf2。
- Android 主仓库：`/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan`，分支 `1.0.3-snapshot`。
- 状态：已提交、推送并部署 perf2；制品与三容器核验通过。指定业务执行已在部署前结束，未进行恢复或拉人验收。

## 根因与范围

10-06 以文件同步方式部署的两项热修复没有提交 Git。10-08 从干净的 `a80cde6` 整目录 rsync 发布，回退了审批缺省值解析和登录准备阶段的注销拦截分类。此次远端 8 文件 SHA-256 均与 `a80cde6` 完全相同；按部署脚本完整排除规则做 checksum dry-run，内容差异恰好为这 8 文件，没有额外热补丁或待删除文件。

- 完整群快照缺少 `membership_approval_mode` 时返回 `off`；节点存在但内容不完整时仍保持未知；显式值保留。单群与批量群采用相同语义。
- 登录准备阶段遇到 `ErrAccountDeletionBlocked` 返回 `1011`，执行器转换为 `NEED_REAUTH`，停止自动上线；普通准备错误保留原错误码并发出 `OFFLINE`，不再伪造代理故障 `302/PROXY_FAILED`。
- 没有后端、前端、Web 协议业务修改，没有新增 DB migration、Redis schema 或配置项。
- 仅提交用户指定的 8 文件。主目录 `.codegraph/daemon.pid` 保留原有脏改，不提交。

## 提交与发布源

1. `5fd85e1` — `fix(group): treat omitted membership approval node as off`
   - `internal/service/node/nodes/iq.go`
   - `internal/service/node/nodes/iq_group_result_test.go`
   - `api/service/group_profile_metadata_test.go`
2. `c54701aa0a8c6408d92fbbcfdbbef0c366b1fabc` — `fix(account): stop auto relogin when deletion lifecycle blocks login`
   - `api/service/login.go`
   - `api/service/login_start_test.go`
   - `api/vo/vo.go`
   - `internal/armada/executor.go`
   - `internal/armada/executor_test.go`

两次提交均逐文件暂存并检查 `git diff --cached --stat` 和 `git diff --cached --check`。连同用户授权的既有 `a80cde6`，以快进方式推送 `origin/1.0.3-snapshot`；`git ls-remote` 确认远端 HEAD 为 `c54701aa0a8c6408d92fbbcfdbbef0c366b1fabc`。

部署使用主仓库新 HEAD 的独立干净检出 `/private/tmp/perf2-android-hotfix-20261009/source`，经 `ARMADA_ZHUAN_DIR` 指定。这样保留主仓库运行中 PID 文件，不通过忽略 Git 变更伪造 clean。dry-run 实际输出：`Zhuan 源码: branch=DETACHED commit=c54701a state=clean`。

## 本地验证

运行环境：`go1.26.5 darwin/arm64`。

| 检查 | 结果 |
|---|---|
| 8 文件 `gofmt -l` | 无输出，已格式化 |
| `git diff --check` | 通过 |
| `go vet ./...` | 通过 |
| `go build ./...` | 通过 |
| `go test ./internal/service/node/nodes/... ./api/service/... ./internal/armada/...` | 全部通过 |
| `go test ./...` | 仅 `pkg/noise` 8 项既有失败，其他包通过；未修改或弱化这些测试 |
| `bash -n armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh` | 通过 |
| `bash armada-deploy/deploy-test.test.sh` | 通过 |
| `bash armada-deploy/package-prod.test.sh` | 既有失败：缺少 `armada-deploy/prod/scripts/inspect-production-host.sh`；本次没有使用生产离线包 |

8 个既有失败：`NoiseSuite.TestHandshakeRollback`、`TestNN`、`TestVectors`、`TestXX`、`Test_NNpsk0`、`Test_Npsk0`、`Test_XXpsk0`、`Test_Xpsk0`。

独立只读复核未发现本次 diff 的发布阻断项。修复范围是登录准备阶段的失败分类，不宣称覆盖所有注销竞态；未进行账号真实注销或重登实验。

## 部署与证据

执行位置：Armada 主仓库。目标：perf2 Zhuan，`/home/ec2-user/whatsapp-android-zhuan/deploy/docker-compose.perf.yml`。

```bash
ARMADA_ZHUAN_DIR=/private/tmp/perf2-android-hotfix-20261009/source \
  bash armada-deploy/deploy-test.sh --env perf2 --zhuan --yes --dry-run
ARMADA_ZHUAN_DIR=/private/tmp/perf2-android-hotfix-20261009/source \
  bash armada-deploy/deploy-test.sh --env perf2 --zhuan --yes
```

部署前完整 checksum 比对：8 个预期内容差异，其他 869 项仅检出文件时间戳差异；无额外内容差异、无新增/删除远端文件。部署前全部容器 `running/healthy`、restarts=0。

- 部署前镜像：`sha256:77eead0b40c137a327b8ea85869a32c027e8724e436b25407f6d781ab4962c56`。
- 部署前运行二进制 `/proc/1/exe` 与 `/app/whatsapp-server` 相同：`8cbafac58a3ccf68ed475545276139aa9d47b2ebe30a2da4a6c35df98b157a8d`。
- 本地验证、SHA-256 清单、完整 rsync dry-run、部署输出：`/private/tmp/perf2-android-hotfix-20261009/`。
- 部署命令退出码 0，Zhuan SUCCESS，Backend / Frontend / Web Protocol 均 SKIPPED；Zhuan deployment 阶段 133 秒。
- 部署后镜像：`sha256:e0eac195f409cb384c68059a68d3e9be2171030dc0b4b15f9233f55ca04d1c6b`，三容器均使用此镜像。
- 部署后运行二进制 `/proc/1/exe` 与 `/app/whatsapp-server` 相同：`c7ed28a96a86546d6c99a5f9285487199e1b42f081aed3c2edb6062d2bcdac06`，与旧版本不同。
- 8 个远端文件 SHA-256 全部等于干净新 HEAD。
- 远端 `iq.go` SHA-256：`b5f69b0f36ee5def77bbb1a3ea5c0cda98857e259b2dcd5cff7daac52c76d6ad`，第 1033、1183 行均包含 `groupJoinState := "off"`。
- 2026-10-09 15:30:44（北京时间）核验结果：

| 容器 | 运行状态 | 健康状态 | 重启次数 |
|---|---|---|---|
| callback-zhuan | running | healthy | 0 |
| whatsapp-android-zhuan | running | healthy | 0 |
| traffic-dashboard-zhuan | running | healthy | 0 |

回滚标签仍指向原镜像。主仓库 HEAD 与 origin 跟踪分支均为 `c54701a`；主目录只保留原有 `.codegraph/daemon.pid` 脏改，发布检出仍为 clean。

## 回滚

部署前已将三个实际运行容器共用的旧镜像保留为：

`whatsapp-server-feature-android-zhuan:before-committed-hotfix-c54701a-20261009`

远端源码备份与清单位于：

`/home/ec2-user/whatsapp-android-zhuan/deploy/rollback/before-committed-hotfix-c54701a-20261009/`

其中 `source-before.tar` 仅含本次 8 文件，不含凭据。归档 SHA-256：`0b3471765acc7835ef14ff6e1f859cdf49ad030a7359ac07d0399e42b7998038`。

如需回滚，先确认仍为 perf2，再在远端执行：

```bash
cd /home/ec2-user/whatsapp-android-zhuan
tar -xf deploy/rollback/before-committed-hotfix-c54701a-20261009/source-before.tar
sudo docker image tag \
  whatsapp-server-feature-android-zhuan:before-committed-hotfix-c54701a-20261009 \
  whatsapp-server-feature-android-zhuan:perf
cd deploy
sudo docker compose -f docker-compose.perf.yml up -d --no-build --force-recreate \
  callback-zhuan whatsapp-android-zhuan traffic-dashboard
```

回滚后需重新确认镜像、运行二进制和三容器健康；不要运行发布脚本重新构建。该回滚会恢复两个已知缺陷，仅用于本次发布出现更严重运行问题时恢复旧制品。本次没有执行回滚。

## 业务验收边界

用户指定任务 `任务_29664388`。2026-10-09 15:30:45（北京时间）通过只读事务查询 `armada_perf`：

- 任务 ID `86`，执行 ID `328`，模式 `SIMPLE_NEW_GROUP`。
- 任务状态已经是 `ENDED`，执行状态 `6`（取消终态），`manual_paused=0`；结束时间 `15:10:17.793`，早于本次部署。没有当前可恢复的暂停执行行。
- 建群角色账号 ID `2787`，`protocol_id=ANDROID`，号码仅记录尾号 `3442`；角色已释放。
- 群资料动作 ID `12430`、type `7`，状态 `3=SUCCESS`，第 1 次尝试，结果时间 `15:08:35.938`。
- `profile_verified_at` 与 `profile_verified_command_id` 仍为空，拉人调用数 `0`。保留的 `GROUP_PROFILE_VERIFICATION_FAILED` 是结束前的原失败原因，不代表当前仍为暂停状态。

因此已确认 Android 建群号与资料动作成功，但**原执行无法通过“恢复”继续**。已告知用户实际终态；后续需要用户选择或创建新的可验收执行，再由用户操作页面。此次没有恢复、拉人、注销、重登录或资料写入，也没有篡改终态。新协议真实审批读回及任务推进尚待业务验收，不能以部署成功代替。
