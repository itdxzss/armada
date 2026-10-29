# test1 四项目发布前检查 — 2026-10-07

用户授权当前四项目部署第一套 test1，冻结源码包含未提交业务改动。Android 仅 coordinator/node1。

- Armada d15683e6；前端 5eed5ef9；Web 协议 5f8a4e5；Android 8d05bb1。精确源码哈希见 sources.json。
- 后端生命周期及 outbox 回归 109/109 通过，包含 H2 事务回滚测试。
- 新群所选回归 50 项：48 通过，2 项既有错误（管理分组选填校验、H2 重复执行序号），与当前 change 记录一致。
- 前端 typecheck 通过，批量操作 15/15 通过。
- Web 制品测试 5/5 通过。
- Android go vet ./...、go build ./... 通过；变更相关 api/service、internal/armada、internal/service/node/nodes 及 deploy/fleet 通过。全量测试 Noise 8 项失败在原工作区复现；快照初次 fleet 失败因无 Git 元数据，补齐后复验通过。
- deploy-test.test.sh 通过。生产离线包测试缺少 inspect-production-host.sh，本次不使用此流程。
- test1 部署前 --check 通过。当前库 V213，与本地迁移末版本一致。
- 完成后端未提交差异的事务边界、拒绝批次缩小、outbox 插入前抛错检查，无此次新增阻断发现。

备份标识 full-20261007-1730；业务与 coordinator/node1 的旧镜像已打 rollback 标签。业务备份 /home/app/armada-deploy-release-backups/full-20261007-1730/artifacts.tar.gz；Android 各自主目录对应 release-backups 路径；Web /home/ec2-user/armada-protocol-release-backups/full-20261007-1730/code.tar.gz。备份仅保留在目标主机。

发布命令：deploy-test.sh --env test1 --full --yes。快照复用本机现有 node_modules，走脚本已有 npm 构建回退路径。未改动业务源码、未提交推送主仓。
