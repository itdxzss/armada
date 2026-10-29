# 双环境发布前检查 — 2026-10-05

用户授权部署今天的当前代码到第一套 test1 和第二套 perf2，范围为 Armada 后端、前端、Web 协议、Android 协议。第一套 Android 仅 coordinator 与 node1，排除已归 Athena/WB Pro 的 node2/node3。

发布源为 1.0.3-snapshot 的实际文件（包含未提交、未跟踪源码），冻结到两个独立构建目录；未 fetch/pull、未切换主仓分支、未向远端提交。源 HEAD 与文件哈希见 sources.json 和 source-sha256.json。

## 验证结果

- 后端 Java 17 聚焦回归：574 项，571 通过，0 assertion failure，3 ERROR。两项为已有 NEW_GROUP 管理分组选填前端与后端校验不一致；一项为同一测试复用草稿时重复 seq 的 H2 唯一键冲突。与今天此前发布记录一致，未将这些在途需求或夹具改动扩为本次发布修复。
- 前端 TypeScript 与 Vue 类型检查通过。相关测试初跑 63 项，61 通过，1 个既有分组标签断言失败、1 个测试文件被 Node CSS 加载阻断；用已有测试替身和临时 tsconfig 对该文件复验 5 项全部通过。分组标签断言问题已有今日记录。
- Android internal/armada 全包测试、go vet ./...、go build ./... 通过。
- Web 协议部署制品测试 5 项通过；两套发布分别执行本地/远端编译。
- deploy-test.test.sh 通过，两套 --full --dry-run 通过，Mapper XML 校验通过。
- package-prod.test.sh 因仓库缺少 prod/scripts/inspect-production-host.sh 失败；本次不使用生产离线包流程。
- test1/perf2 部署前 --check 均退出码 0。
- 本机 pnpm 11 对快照的共享 node_modules 尝试自动安装并因非交互式删除保护而退出；发布选择部署脚本已有 npm run build 回退路径，复用现有依赖，未修改依赖版本或主仓 node_modules。

## 备份

备份标识 full-20261005-1825。

- 两套业务服务：/home/app/armada-deploy-release-backups/full-20261005-1825/artifacts.tar.gz。
- 第一套 coordinator/node1：各用户主目录下 whatsapp-server-feature-android-zhuan-release-backups/full-20261005-1825/code.tar.gz。
- 第二套 Android：/home/ec2-user/whatsapp-android-zhuan-release-backups/full-20261005-1825/code.tar.gz。
- 两套 Web 协议：/home/ec2-user/armada-protocol-release-backups/full-20261005-1825/code.tar.gz。
- 现有应用与 Android 镜像保留 <container-name>:rollback-full-20261005-1825 标签。

未发出真实 WhatsApp 消息或创建业务验收任务。部署与健康检查不替代真实配对、发信、拉群验收。

回滚注意：本次新群名称包含冻结序号。若回滚到旧程序，应先暂停本次新建的相关任务，保留原群 JID 和冻结名称，避免旧执行器覆盖或拒绝新群名。
