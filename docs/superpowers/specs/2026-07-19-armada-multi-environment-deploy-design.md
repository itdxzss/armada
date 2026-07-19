# Armada 多环境统一部署设计

- 日期：2026-07-19
- 状态：设计已确认，待用户审阅书面版本
- 范围：第一套测试环境、第二套性能环境；Armada 后端、Vue 前端、Baileys 协议层、Android Zhuan

## 1. 目标

在不复制整套部署脚本的前提下，让现有 armada-deploy 同时支持第一套和第二套环境：

~~~bash
./armada-deploy/deploy-test.sh --env test1 --full
./armada-deploy/deploy-test.sh --env perf2 --full
~~~

第二套的 full 部署必须覆盖：

1. Armada Spring Boot 后端；
2. wheel-saas-pure-web 前端和 Nginx；
3. armada-protocol Baileys master 与 worker；
4. Android Zhuan 与 callback。

不指定 --env 时继续使用第一套环境；不带任何参数时继续只显示部署指引，不执行部署。

## 2. 当前事实

### 2.1 本地代码

1. armada-deploy/deploy-test.sh 目前约 865 行，把参数解析、构建、SSH、rsync、远端启动和验活放在同一个文件中。
2. 脚本默认值全部指向第一套环境，只能通过多组 ARMADA_* 环境变量手工覆盖。
3. 脚本已经能按第二套 Armada 主机推断“第二套环境”标题，但没有显式环境档案。
4. --full 已包含后端、前端、Baileys 和 Zhuan，但 Zhuan 固定使用第一套 docker-compose.yml 和旧远端目录。
5. Zhuan 仓库已经包含 docker-compose.perf.yml、Redis Cluster/TLS/prefix 支持和 RDS CA 校验。
6. Armada Compose 模板已经包含 PROTOCOL_ANDROID_BASE_URL 透传能力。

### 2.2 2026-07-19 只读远端核验

第二套 Armada：

- 远端目录为 /home/app/armada-deploy；
- .env 存在，DB_URL 指向 armada_perf；
- 三个 Android command topic 均为 armada.perf.protocol.android.*；
- PROTOCOL_ANDROID_BASE_URL 已写入 .env；
- armada-backend 和 armada-nginx 正在运行；
- 远端 Compose 仍缺少 Android URL 透传，运行中 backend 尚未取得该变量。

第二套 Zhuan：

- Compose project 为 whatsapp-android-zhuan-perf；
- 实际目录为 /home/ec2-user/whatsapp-android-zhuan；
- 使用 deploy/docker-compose.perf.yml；
- whatsapp-android-zhuan 与 callback-zhuan 均为 healthy；
- 没有本地 redis-zhuan 容器；
- 运行时配置使用 android-zhuan-perf:、whatsapp_android_zhuan_perf、三个 perf command topic、三个独立 consumer group 和现有 perf event topic；
- RDS CA 文件存在；
- 最近 30 分钟未检测到 MySQL、Redis 或 Kafka 依赖连接错误。

第二套 Baileys：

- 协议机私网地址为 172.31.8.217；
- 经第二套 Armada 主机跳转 SSH 时，目标端口连接超时；
- 当前未确认其它可用公网部署入口。

### 2.3 事实、推断和未完成外部状态

- 事实：第二套 Armada 与 Zhuan 的隔离配置已经存在，Zhuan 已健康运行。
- 推断：结合健康状态和近期无依赖错误，现有 RDS、Redis、MSK 基础设施大概率已经可用。
- 未执行：本次设计阶段没有运行 Kafka topic describe、数据库授权查询或 Redis ACL 深度检查。
- 外部缺口：第二套 Baileys 的 SSH 路由尚未打通；实施时采用 Armada 跳板机访问协议机私网地址，并只开放协议机 TCP 22 给 Armada 安全组。修改安全组前再次确认目标为第二套性能环境。

## 3. 方案选择

选择“统一入口 + 非敏感环境档案 + 组件部署模块”。

不采用：

1. 复制 deploy-perf.sh：第一、第二套逻辑会独立演进，修复容易漏同步。
2. 继续向单文件堆 case：短期改动少，但脚本职责已经过多，第二套的跳板、PM2 和 perf Compose 会继续放大复杂度。

## 4. 目录与模块

~~~text
armada-deploy/
├── deploy-test.sh
├── envs/
│   ├── test1.conf
│   └── perf2.conf
└── lib/
    ├── common.sh
    ├── armada.sh
    ├── protocol.sh
    └── zhuan.sh
~~~

职责：

- deploy-test.sh：解析参数、选择 scope、加载环境、编排执行顺序和汇总结果；
- common.sh：日志、输入校验、SSH/rsync 参数、直接或跳板连接、dry-run 和公共等待函数；
- armada.sh：后端与前端的构建、同步、Compose 启动和验活；
- protocol.sh：Baileys 源码同步、远端 Node 构建、PM2 reload 和 master/worker 验活；
- zhuan.sh：受保护同步、按环境选择 Compose、迁移、启动和验活；
- envs/*.conf：只保存可提交的部署拓扑、启动方式和隔离约束。

部署模块不得根据 IP 猜测环境；所有行为来自已加载并验证的 profile。

## 5. 环境档案

### 5.1 可提交内容

环境档案允许保存：

- 环境 ID 和界面标题；
- 主机地址、SSH 用户、私钥文件路径；
- 远端目录、端口、Compose project 和 Compose 文件；
- PM2 配置名、直接连接或跳板连接模式；
- 访问入口；
- 预期 schema、topic、consumer group 和 Redis prefix。

示意：

~~~bash
ENV_ID=perf2
APP_TITLE=第二套环境

ARMADA_HOST=3.110.124.52
ARMADA_USER=ec2-user
ARMADA_KEY_PATH=测试pem/armada-perf.pem
ARMADA_REMOTE_DIR=/home/app/armada-deploy
ARMADA_COMPOSE_FILE=docker-compose.rds.yml

PROTOCOL_HOST=172.31.8.217
PROTOCOL_USER=ec2-user
PROTOCOL_KEY_PATH=测试pem/armada-protocol-perf.pem
PROTOCOL_SSH_MODE=jump

ZHUAN_HOST=3.111.245.182
ZHUAN_USER=ec2-user
ZHUAN_KEY_PATH=测试pem/android-protocol.pem
ZHUAN_REMOTE_DIR=/home/ec2-user/whatsapp-android-zhuan
ZHUAN_COMPOSE_FILE=deploy/docker-compose.perf.yml

EXPECTED_ARMADA_SCHEMA=armada_perf
EXPECTED_ZHUAN_SCHEMA=whatsapp_android_zhuan_perf
EXPECTED_REDIS_PREFIX=android-zhuan-perf:
EXPECTED_TOPIC_PREFIX=armada.perf.
~~~

### 5.2 禁止提交内容

以下内容不得进入 profile、Git、日志或变更记录：

- 私钥内容；
- 数据库、Redis 或 Kafka 密码；
- 完整数据库、Redis 或 broker 连接串；
- API key、token、证书私钥；
- 远端真实 .env；
- Zhuan 真实 prod_configs.toml。

私钥路径可以提交，私钥文件本身继续位于仓库外或被 Git 忽略。脚本只检查文件存在和权限，不读取或打印内容。

### 5.3 加载和覆盖规则

1. --env 只接受 test1 或 perf2，不接受任意文件路径。
2. profile 从仓库固定目录加载，并校验必需字段。
3. 现有 ARMADA_* 覆盖变量继续保留，以兼容第一套现有用法。
4. 所有覆盖后的主机、目录和连接模式必须打印在部署计划中。
5. EXPECTED_* 隔离约束不可通过进程环境变量覆盖，防止临时覆盖绕过第二套保护。
6. APP_TITLE 来自 profile 或明确覆盖，不再依赖 IP 推断作为主要行为。

## 6. 命令接口与兼容性

保留现有命令：

- --all：后端 + 前端；
- --full：后端 + 前端 + Baileys + Zhuan；
- --be、--fe、--protocol、--zhuan：单组件；
- --branch：仅控制 Armada 远端分支 worktree，帮助文本必须明确不控制其它三个仓库；
- --dry-run、--logs、-y/--yes。

新增：

- --env test1|perf2：选择环境；
- --check：对所选环境做深度检查，不构建、不同步、不重启。

不指定 --env 时等价于 --env test1。部署计划必须同时打印环境 ID、目标主机、远端目录、Compose/PM2 入口以及四个仓库的 commit 和 dirty 状态。

## 7. 分层检查

### 7.1 日常快速检查

默认部署只检查本次 scope 必需项：

- --be：JDK、Maven、Armada SSH、远端 .env 必需字段；
- --fe：前端工具、Armada SSH、前端目录；
- --protocol：协议仓库、协议机 SSH 路由、远端 .env；
- --zhuan：Zhuan 仓库、目标机 SSH、TOML、证书和所选 Compose；
- --full：上述四组快速检查。

快速检查不查询 Kafka 分区、数据库授权、Redis ACL 或完整安全组状态。

### 7.2 部署后生效检查

部署后只验证本次部署的组件，但不能省略：

- Baileys：PM2 master 和四个 worker online，healthz/readyz 可用；
- Zhuan：主容器和 callback healthy，没有本地 Redis，HTTP 健康端点可用；
- backend：等待 Spring Boot 真正就绪后再检查 API 和运行时关键变量；
- frontend：HTML、platform-config.json、环境标题和 /api 代理正确。

### 7.3 深度检查

--check 用于首次接入、环境档案变化、新增基础设施或排障，检查：

- Kafka topic、分区、consumer group 和环境前缀；
- RDS schema；
- Redis namespace、TLS 和可用性；
- 四个组件之间的网络连通；
- 远端配置与 profile 隔离约束的一致性。

--check 不创建基础设施。发现新增或缺失项时停止并输出脱敏缺口；基础设施由单独、可审计的一次性操作补建，执行前确认目标环境。

## 8. Full 部署数据流

~~~text
加载 perf2 profile
  -> 快速检查四个 scope
  -> 完成本地后端和前端构建
  -> 部署并验活 Baileys
  -> 部署并验活 Zhuan
  -> 部署并等待 Armada backend 就绪
  -> 部署并验活前端/Nginx
  -> 输出组件结果
~~~

顺序固定为 Baileys -> Zhuan -> backend -> frontend。两个协议实现先于调用方部署；任一协议失败，不继续更新 Armada。

单组件 scope 只运行对应模块，不因为依赖顺序额外部署其它组件。

## 9. 各组件关键行为

### 9.1 Armada 与前端

- 后端 jar 和前端 dist 继续本地构建；
- .env 只在远端维护，脚本永不覆盖；
- 同步当前 Compose 模板，使第二套 PROTOCOL_ANDROID_BASE_URL 进入 backend；
- backend 启动检查改为带超时重试，避免 Spring 未就绪时立即请求产生偶发 502；
- 前端继续保留旧 hash chunk，避免浏览器缓存引用失效。

### 9.2 Baileys

- 第一套保持现有直接 SSH；
- 第二套通过 Armada 跳板机访问协议机私网地址；
- 实施前打通协议机安全组 TCP 22，仅允许 Armada 安全组来源，不开放公网 SSH；
- 沿用远端 npm ci、npm run build 和 PM2 startOrReload；
- 保留远端 .env，验证 Node 24、master 和四个 worker。

### 9.3 Zhuan

- 第一套使用 deploy/docker-compose.yml；
- 第二套使用 deploy/docker-compose.perf.yml；
- 第二套实际远端根目录固定为 /home/ec2-user/whatsapp-android-zhuan；
- rsync 必须排除 .env、prod_configs.toml、证书、日志和其它敏感运行文件；
- Compose 文件、迁移和健康检查由 profile 决定；
- 第二套必须拒绝本地 redis-zhuan、错误 schema、非 perf topic 或错误 Redis prefix。

## 10. 失败与回滚

--full 不是跨四个系统的原子事务，不做自动全局回滚。

规则：

1. 所有本地构建先成功，再开始远程部署。
2. 任一远程步骤失败立即停止，后续组件不执行。
3. 已成功组件保持当前版本，不自动切回旧版本。
4. 最终输出成功、失败、未执行的组件清单和脱敏原因。
5. 修复后使用单组件 scope 重试。
6. 不执行 docker compose down -v，不删除 schema、topic、Redis 数据、日志或证书。
7. 所有远端目录在 rsync 前校验，拒绝空值、根目录、相对路径、重复斜杠和 .. 路径段。

自动全局回滚和版本化 release 目录不在本次范围内；它们会扩大改动和存储管理复杂度，而当前已有单组件重发能力。

## 11. 测试

自动化测试不访问真实服务器，使用临时目录和 SSH、rsync、Docker、PM2 stub 验证：

1. 未指定 --env 时保持第一套行为；
2. perf2 使用正确目标、远端目录、跳板模式和 perf Compose；
3. 各 scope 只调用对应模块；
4. --dry-run 不构建、不 SSH、不 rsync、不重启；
5. 默认只做快速检查，--check 才做深度检查；
6. 敏感文件和日志不会被同步覆盖；
7. 输出不包含密码、连接串、token 或私钥内容；
8. 协议失败后 backend 和 frontend 不执行；
9. backend 等待就绪并正确处理超时；
10. 危险远端目录被拒绝；
11. 现有 deploy-test.test.sh 合同继续通过。

聚焦验证命令：

~~~bash
bash -n armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh
bash armada-deploy/deploy-test.test.sh
node armada-deploy/verify-config.mjs
bash armada-deploy/package-prod.test.sh
./armada-deploy/deploy-test.sh --env test1 --full --dry-run
./armada-deploy/deploy-test.sh --env perf2 --full --dry-run
~~~

真实 SSH、--check 和部署验证只在再次确认第二套环境和目标源码 commit 后执行。

## 12. 验收标准

1. 第一套现有命令无需修改即可继续使用。
2. perf2 可用一条 --full 命令部署四个组件。
3. 四个单组件 scope 在 perf2 可独立执行。
4. 第二套 Zhuan 自动选择 perf Compose 和正确远端目录。
5. 第二套 Baileys 通过私网跳板部署，不开放公网 SSH。
6. 第二套 backend 运行时取得 PROTOCOL_ANDROID_BASE_URL 和三个 perf Android topic。
7. 日常部署不执行深度基础设施扫描。
8. --check 能独立报告环境一致性，不产生外部写入。
9. 任何输出和同步路径都不泄露或覆盖敏感配置。
10. 任一协议部署失败时，Armada 不继续更新。

## 13. 本次范围外

- 不新增自动基础设施创建器；
- 不自动修改数据库、Kafka、Redis 或安全组，第二套 Baileys 的一次性 SSH 安全组修复除外，且必须再次确认；
- 不引入 Ansible、Terraform、Kubernetes 或 CI/CD 平台；
- 不改变应用业务逻辑；
- 不设计四仓统一 release manifest；
- 不实现跨组件自动回滚。

## 14. 实施顺序

1. 用测试锁定 profile、scope、dry-run、跳板和 perf Compose 合同；
2. 拆出 common、armada、protocol、zhuan 模块，保持第一套行为；
3. 增加 test1/perf2 profile 和 --env；
4. 增加快速检查与独立 --check；
5. 修复第二套 Armada Compose 同步和 backend 就绪等待；
6. 确认第二套环境后打通 Baileys 私网 SSH；
7. 运行本地测试和两个 profile 的 dry-run；
8. 再次确认 commit 与范围后，执行第二套分组件验证和 full 验收。
