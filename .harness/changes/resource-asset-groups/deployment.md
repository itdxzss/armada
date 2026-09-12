# 图片分组与业务隔离 test1 发布

2026-09-12。用户要求将当前代码 commit、push，再部署第一套环境。本轮已完成前后端两个主仓库；协议仓库未提交或重新部署，保留第一套已有的引用回复协议版本。

| 仓库 | 已推送的部署代码 commit | 分支 |
|---|---|---|
| armada | 7130b386eb43aeaaf528e85e9515ff6d072a45a1 | 1.0.3-snapshot |
| wheel-saas-pure-web | fd619c53 | 1.0.3-snapshot |

现有业务源码、测试、设计与既有验收记录一并提交；未纳入 .codegraph 运行元数据和嵌套工作树。旧只读 SQL 证据的尾部制表符保持原样，避免改写原始记录。未提交任何凭据。

## 验证与发布

- JDK 17 下 30 个聚焦测试类共 278 用例通过，0 失败 / 错误 / 跳过；包含素材 H2、租户与事务、引用回复及协议 wire 回归。
- 前端类型检查、改动范围 ESLint、15 项领域用例通过；本轮 production build 通过，上一轮本地浏览器分组/养群范围 4 项通过。
- deploy-test.test.sh 通过。生产离线包检查因既有 inspect-production-host.sh 缺失失败；该文件不在本次 test1 部署路径，本次未修改或绕过测试环境检查。
- 使用当前 deploy-test.sh --env test1 --all --branch 1.0.3-snapshot -y；后端从刚推送的远端分支临时工作树构建，前端从已提交且干净的主工作区构建。
- 临时 PATH 选择已安装 Node 24/npm 和现有前端依赖，使用脚本支持的 npm build 分支，未重装依赖或改锁文件。
- 部署退出 0：Backend SUCCESS、Frontend SUCCESS；Protocol / Zhuan SKIPPED。

## 实际生效证据

- V188 校验值与原环境相同；V189、V190 success=1；失败迁移数 0。
- 现有 62 条图片记录全部 asset_scope=NULL；迁移未将任何旧记录改为某一业务。此数量包含已有软删除记录，不等于某个租户列表的可见数量。
- 新归属列与按业务分组关系表实际存在；旧 file.group_id 已按迁移移除。
- 后端和 Nginx running、restart=0，启动以来 ERROR 行数 0。
- 本地 JAR 与运行容器 JAR SHA256 一致：abbe1ddf6a0385ac218f51fb5c93bbb941bc6119e703cfbc78c8f5c69d33b7aa。
- 554 个前端静态文件摘要一致；环境标题为“第一套环境”。
- --env test1 --check 退出 0；Armada、Baileys、Zhuan、跨组件和 Kafka 检查通过。
- 公网素材 API scope=SCRIPT 未登录请求返回 HTTP 401。
- 浏览器新标签页进入登录页，随后借用现有素材页被用户取消；已停止并关闭自动化 session。未完成真实登录态的上传/移组/删组交互验收，未发送 WhatsApp 消息，也未创建测试图片或分组。

结构文档以真实 information_schema 生成三张素材表，未手工修改数据库。操作时间和容器制品证据见 artifact-verification.json；Flyway 与数据保留结果见 postflight.txt。本记录及结构文档的后续提交只更新文档，无需重新部署。
