# 变更记录：Zhuan fleet 四机并发部署

- 日期 / 分支 / worktree: 2026-08-11 / `1.0.3-snapshot` / 当前工作区
- 需求来源: 用户要求第一套 Zhuan 的 coordinator 与 3 个 node 同步部署，其他部署提速建议一并实施
- 状态: 已完成

## 目标（一句话）

第一套 Zhuan fleet 只构建一次镜像，并发完成四台目标的同步、镜像加载、重启与验活。

## 缺口拆解 / 任务清单

- [x] 连通性预检改为四机并行 SSH，不再执行重复的 rsync dry-run
- [x] 本地只构建、导出一次 Linux 镜像
- [x] coordinator 与 3 个 node 并发同步、加载、重启和验活
- [x] 排除 `.codegraph`，输出每台目标的分阶段耗时
- [x] 完成 Armada 与 Zhuan 相关部署回归

## 关键设计决策

- lifecycle inbox 排空检查在并发部署前只执行一次，保留旧 Stream 升级保护。
- 四台目标不再逐台滚动，测试环境发布速度优先；部署窗口不承诺持续可用。
- 任一目标失败时等待其他已启动目标结束，最终整体返回非零，保留所有目标结果用于排障。

## 验证（evidence-before-done）

- `bash armada-deploy/deploy-test.test.sh`：通过。
- `go test ./deploy/fleet ./deploy/node -count=1`：通过。
- `go vet ./...`、`go build ./...`：通过。
- `go test ./...`：部署相关包通过；既有 `pkg/noise` 套件失败，包含缺少 `vectors.txt` 与随机向量断言不一致，与本次部署脚本改动无关。
- `bash armada-deploy/package-prod.test.sh`：既有门禁失败，缺少 `armada-deploy/prod/scripts/inspect-production-host.sh`，与本次测试环境 Zhuan 改动无关。

## 部署

- 未部署；本次未连接任何远程环境。

## 遗留 / 跟进

- 首次真实部署后根据分阶段耗时判断瓶颈是否转移到本地 buildx 或上行带宽。
