# 第一套 test1 四项目发布完成 — 2026-10-07

发布结束于北京时间约 17:41。四组件部署脚本退出码 0，Protocol / Zhuan / Backend / Frontend 均 SUCCESS；发布后 --check 退出码 0。

## 发布内容

| 项目 | 源 HEAD | 范围 | 结果 |
|---|---|---|---|
| armada | d15683e6 | 后端，包含冻结时未提交业务源码 | 成功 |
| wheel-saas-pure-web | 5eed5ef9 | 前端，包含冻结时未提交业务源码 | 成功 |
| armada-protocol | 5f8a4e5 | Web master / 4 workers / dashboard | 成功 |
| whatsapp-server-feature-android-zhuan | 8d05bb1 | coordinator + node1，包含冻结时未提交业务源码 | 成功 |

未部署 perf2、node2 或 node3。共享主机 Athena 容器保持之前的启动时间。主仓业务文件未修改，未进行 commit/push。

## 验证

- 后端本地 JAR、远端 JAR、运行容器 JAR SHA-256 一致：2d46657b83d1e63efe4aa8cdbb6fd667e10766234ab8d7d78511f60ae2e61627。
- 前端本地首页、nginx 容器首页及公网首页 SHA-256 一致：5a8a2df86545f72086bcd07ea01a98c4d6c709e64807b0b7c571b474e8332e78。
- Web server 源码及编译输出哈希一致；PM2 全部 online，readyz ok。PM2 累计重载计数非零，未将其描述为零重启。
- Android 两台关键修改源码哈希一致，新镜像已切换，容器 healthy，RestartCount=0。
- Armada backend/nginx running，RestartCount=0。
- Flyway 校验 215 个迁移成功；schema armada 当前 V213，无迁移待执行。
- 公网首页访问成功，匿名 /api/account-groups 返回 HTTP 401 / code 40104。
- 深度检查包含配置隔离、Kafka、Zhuan 和跨组件契约，全部通过。

## 测试与限制

定向生命周期/outbox 后端回归 109/109、前端类型检查和批量操作 15/15、Web 制品 5/5、Android vet/build 及相关业务包/fleet 测试通过。

测试并非全绿：新群所选回归 50 项中存在 2 项已记录错误；Android Noise 8 项失败在原工作区复现；生产离线包脚本测试缺少文件。详情见 preflight.md。没有执行真实 WhatsApp 配对、发信、拉群或永久注销验收。

## 回滚

备份标识 full-20261007-1730，原制品与配置留在各自目标主机 release-backups 目录，旧业务/Android 镜像保留 rollback-full-20261007-1730 标签。Web 可从 code.tar.gz 恢复本次覆盖的源代码/产物及配置后重新加载。此次无数据库版本变化。

## 证据

- sources.json：冻结源码版本和逐文件哈希。
- runtime.json：运行状态、迁移结果、制品哈希和公网接口结果。
- precheck.txt / postcheck.txt：部署前后深度检查。
- deploy-result.txt：四组件结果及阶段耗时。
- preflight.md：测试与备份详情。
- 完整部署日志：/private/tmp/armada-test1-deploy-20261007.log。
