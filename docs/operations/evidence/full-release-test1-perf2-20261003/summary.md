# 第一套、第二套四项目发布 — 2026-10-03

两套 full 部署退出码均为 0，部署后只读深度检查退出码均为 0。

## 范围与源码

- test1 = 第一套；perf2 = 第二套。
- 后端 0a900d18，前端 583caf51 + 原有未提交改动及本次冲突合并，Web 协议 d8c35e1，Android 协议 a220f15。
- 使用固定源码快照与独立构建目录，未从远端分支覆盖本地改动。
- 用户明确第一套安卓仅一个节点，另外两个节点归雅典娜。本次 test1 fleet 清单仅 coordinator + node1，expected online=1；未连接或部署 node2/node3。
- 单节点调整仅在本次发布快照的环境档案与 fleet 清单中实施；原仓库默认 test1 环境档案仍为三节点，后续部署应沿用本次收窄清单或先更新默认配置。

## 验证

- 后端针对性测试 101 个通过（Java 17；JVM 附加需要沙箱外运行）。
- 前端针对性测试 46 个通过；类型检查、冲突文件 ESLint、构建通过。
- Web 协议针对性测试 81 个通过；Android go vet、go build、internal/armada 与 api/service 测试通过。
- deploy-test.test.sh 通过。package-prod.test.sh 因仓库缺少 prod/scripts/inspect-production-host.sh 失败；本次为测试环境发布，没有使用该生产包流程。
- 两套部署脚本后端/前端/Baileys/Zhuan 四组件均 SUCCESS。
- 两套部署后 --check 验证 Armada、Baileys、Kafka、Zhuan、跨组件配置全部通过。
- 两套 V208/V209/V210 迁移均 success=true，三个新增列均存在。
- 后端本地、上传、运行容器 JAR 哈希一致。
- 前端本地、Nginx 容器、公开 HTTP 首页哈希一致，平台标题分别为第一套环境、第二套环境。
- 后端与 Nginx 容器均 running，restart count=0；第一套 node1 与 coordinator healthy，唯一在线节点 01。
- 验证覆盖部署、制品、服务及配置。未通过创建真实 WhatsApp 任务证明配对/发信/拉群业务结果。

## 回滚

- 两套 Armada 旧镜像均保留 rollback:sync-20261003 标签，旧制品与表结构/Flyway 记录位于服务器 /home/app/armada-deploy/release-backups/sync-20261003/。
- 协议旧代码及 Android 旧镜像保留在对应服务目录旁的 *-release-backups/sync-20261003/。
- 迁移为新增列与历史新列回填，应用回滚优先保留数据库新增结构；未执行数据库回滚或手工 ALTER。

## 本地详细日志

- /private/tmp/armada-test1-20261003-deploy.log
- /private/tmp/armada-perf2-20261003-deploy.log
- /private/tmp/armada-test1-20261003-postcheck.log
- /private/tmp/armada-perf2-20261003-postcheck.log

## 入口

- 第一套：http://armada.65.2.123.53.nip.io/
- 第二套：http://armada.3.110.124.52.nip.io/
