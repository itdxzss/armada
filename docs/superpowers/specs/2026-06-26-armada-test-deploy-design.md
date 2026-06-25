# armada 测试环境一键部署设计

- 日期: 2026-06-26
- 状态: 已批准, 待实现
- 主题: 为 armada + wheel-saas-pure-web 建立独立测试环境发版脚本, 并规划 armada-protocol 在现有协议机上的旁路部署

## 1. 背景与目标

armada 是 wheel 重构后的新后端, `wheel-saas-pure-web` 是新的 L3 SaaS 前端。现在需要把二者部署到测试环境, 供真实 RDS、真实前端、真实协议层链路联调。

已确认的约束:

1. 数据库继续使用旧测试环境同一套 RDS, 但 schema 必须分开。
2. Web/API 测试环境按旧 wheel 测试服的模式部署, 但使用独立 compose 项目、独立端口、独立远端目录。
3. 部署脚本放在 `armada/armada-deploy/`, 前端从兄弟目录 `../wheel-saas-pure-web` 构建。
4. `armada-protocol` 可以部署到现有协议机器, 但必须旁路隔离, 不替换现役 `protocol-layer.service`。

## 2. 已探查的现状

旧 wheel 测试服:

| 项 | 值 |
|---|---|
| 主机 | `65.2.123.53` |
| 远端目录 | `/home/app/wheel-deploy` |
| 运行方式 | Docker Compose |
| Compose 项目 | `wheel-deploy` |
| 容器 | `wheel-backend`, `wheel-nginx` |
| 端口 | `80` SaaS, `8081` Admin |
| 资源 | 8G 内存级别, 根盘 48G, Docker build cache 约 23GB |

现役协议机:

| 项 | 值 |
|---|---|
| 主机 | `13.234.217.33` |
| 运行方式 | systemd + Node |
| 服务 | `protocol-layer.service` |
| 工作目录 | `/home/ubuntu/laqunxitong/protocol-layer` |
| 端口 | `8080` |
| 配置 | `/home/ubuntu/protocol.env` |
| Redis prefix | `unsea:` |
| worker | `worker-1` |

本地项目:

| 项目 | 形态 |
|---|---|
| `armada/armada-api` | Spring Boot 3.3.5, Java 17, MyBatis-Plus, Flyway, 默认端口 `8080` |
| `wheel-saas-pure-web` | Vue 3 + pure-admin-thin, pnpm, 已有前端 Dockerfile, 但无 armada 组合部署编排 |
| `armada-protocol` | 从现役协议层拆出的独立仓, 起步与 wheel 协议层一致, 自带协议层 Dockerfile 和开发 compose |

## 3. 方案选择

采用本地 prebuilt 一键发版。

方案:

1. 本地用 JDK 17 构建 `armada-api` fat jar。
2. 本地用 pnpm 构建 `wheel-saas-pure-web/dist`。
3. 通过 rsync 把 jar、dist、部署编排文件发送到测试服 `/home/app/armada-deploy`。
4. 远端 Docker 只 COPY 预构建产物并启动 `armada-backend` 与 `armada-nginx`。

选择理由:

1. 旧测试服已有较大的 Docker build cache, 继续在远端跑 Maven/pnpm 会放大磁盘压力。
2. 本地 prebuilt 失败更容易定位, 不会把服务器卡在半构建状态。
3. 后续重复发版速度更快, 更接近旧 wheel 的 `--prebuilt` 快速通道。

明确不采用:

1. 远端源码构建: 简单但慢, 且更依赖服务器磁盘和网络。
2. 只发后端: 无法形成完整 Web/API 联调入口。
3. 复用旧 `wheel-deploy` compose: 会混淆容器名、端口和回滚边界。

## 4. Web/API 部署设计

### 4.1 本地目录

新增目录:

```text
armada/
└── armada-deploy/
    ├── .env.example
    ├── backend.prebuilt.Dockerfile
    ├── deploy-test.sh
    ├── docker-compose.rds.yml
    ├── nginx.conf
    └── nginx.prebuilt.Dockerfile
```

`armada-deploy` 只负责测试环境部署, 不承载业务代码。

### 4.2 远端目录

远端 bundle 根目录:

```text
/home/app/armada-deploy/
├── .env                         # 只在服务器上维护, 脚本不得覆盖
├── backend.prebuilt.Dockerfile
├── docker-compose.rds.yml
├── nginx.conf
├── nginx.prebuilt.Dockerfile
├── armada-api/
│   └── target/
│       └── armada-api-1.0.0-SNAPSHOT.jar
└── wheel-saas-pure-web/
    └── dist/
```

远端不要求是 git 仓库。部署脚本每次同步编排文件和产物, 但保护 `.env`。

### 4.3 Compose

Compose 项目名: `armada-deploy`

服务:

| 服务 | 容器名 | 镜像来源 | 端口 |
|---|---|---|---|
| backend | `armada-backend` | `backend.prebuilt.Dockerfile` | 容器内 `8080` |
| nginx | `armada-nginx` | `nginx.prebuilt.Dockerfile` | 宿主 `18080` -> 容器 `80` |

访问入口:

```text
http://65.2.123.53:18080/
```

Nginx 行为:

1. `/` 托管 `wheel-saas-pure-web/dist`。
2. `/api/` 同源反代到 `http://backend:8080`。
3. `index.html` 不缓存。
4. hash 静态资源长期缓存。
5. 长耗时 API 的 `proxy_read_timeout` 设置为 120 秒。

### 4.4 后端环境变量

`.env` 由服务器保存真实配置, `.env.example` 只给模板。

核心变量:

```env
DB_URL=jdbc:mysql://<rds-endpoint>:3306/armada?useSSL=false&serverTimezone=UTC&characterEncoding=utf8mb4
DB_USER=<existing-or-new-rds-user>
DB_PASSWORD=<secret>
DEV_LOGIN_PASSWORD=<staging-login-password>
JAVA_TOOL_OPTIONS=-Xms1024m -Xmx1024m
```

说明:

1. `DB_URL` 指向独立 schema `armada`。
2. Flyway 在 `armada-api` 启动时自动迁移该 schema。
3. 发版脚本不创建、删除、清空 schema。
4. schema 初始化或授权由人工或单独数据库脚本完成, 不和应用发版混在一起。

### 4.5 部署脚本

脚本路径:

```text
armada/armada-deploy/deploy-test.sh
```

默认行为:

```bash
./armada-deploy/deploy-test.sh
```

步骤:

1. 检查 SSH 私钥、JDK 17、Maven、pnpm、前端兄弟目录是否存在。
2. 检查 SSH 可达。
3. 打印部署计划并要求确认, `-y` 可跳过确认。
4. 本地构建后端 jar。
5. 本地构建前端 dist。
6. rsync 编排文件、jar、dist 到 `/home/app/armada-deploy`。
7. 远端执行 `docker compose --env-file .env -p armada-deploy -f docker-compose.rds.yml up -d --build`。
8. 验证容器状态。
9. 验证 `http://127.0.0.1:18080/` 返回前端 HTML。
10. 验证 `http://127.0.0.1:18080/api/...` 的后端可达性。

命令选项:

| 选项 | 行为 |
|---|---|
| `-y`, `--yes` | 跳过确认 |
| `--be` | 只构建和重启 backend |
| `--fe` | 只构建和重启 nginx |
| `--logs` | 部署后 tail backend 日志 |
| `--dry-run` | 只打印计划, 不同步、不构建、不重启 |

脚本不得做的事:

1. 不覆盖远端 `.env`。
2. 不清空 RDS schema。
3. 不执行 `docker compose down -v`。
4. 不清理 Docker build cache, 除非后续用户单独确认。
5. 不触碰旧 `wheel-deploy` 容器和端口。

## 5. armada-protocol 旁路部署设计

`armada-protocol` 部署到现有协议机是可行的, 但必须旁路隔离。

不允许:

1. 不停止、不替换 `protocol-layer.service`。
2. 不占用 `8080`。
3. 不复用 `REDIS_KEY_PREFIX=unsea:`。
4. 不复用 wheel 协议层 Kafka topic。
5. 不把 armada 账号运行态写进 wheel 协议层 namespace。

推荐第二阶段单独落地:

| 项 | 值 |
|---|---|
| 服务名 | `armada-protocol.service` |
| 工作目录 | `/home/ubuntu/armada-protocol/protocol-layer` |
| 端口 | `18081` |
| env 文件 | `/home/ubuntu/armada-protocol.env` |
| API key | 独立生成 |
| Redis prefix | `armada:` |
| MySQL schema | `armada_protocol` |
| Kafka client id | `armada-protocol` |
| Kafka topics | `armada.protocol.account.events.v1` 等独立 topic |
| DLQ 目录 | `/var/lib/armada-protocol/dlq` |

启动方式优先沿用 systemd + Node, 与现役协议机当前运维形态一致。Docker 部署可作为后续选择, 但不能直接使用仓库里的开发 compose, 因为该 compose 会尝试占用 `6379/9092/3306/8080-8084`, 与现有机器和外部依赖规划冲突。

armada Web/API 初期可以继续指向现役协议层 `13.234.217.33:8080`。当 `armada-protocol` 旁路服务验证通过后, 再把 armada API 的协议层地址切到 `http://13.234.217.33:18081`。

## 6. 数据流

初期链路:

```text
Browser
  -> http://65.2.123.53:18080/
  -> armada-nginx
  -> /api/*
  -> armada-backend:8080
  -> RDS schema armada
  -> 现役协议层 13.234.217.33:8080
```

协议旁路验证后链路:

```text
Browser
  -> http://65.2.123.53:18080/
  -> armada-nginx
  -> armada-backend:8080
  -> RDS schema armada
  -> armada-protocol 13.234.217.33:18081
  -> RDS schema armada_protocol / Redis prefix armada: / armada Kafka topics
```

## 7. 验证标准

Web/API 部署完成后必须满足:

1. `docker ps` 显示 `armada-backend` 和 `armada-nginx` 均 running。
2. `http://65.2.123.53:18080/` 返回前端页面。
3. `/api` 经 Nginx 能到达 `armada-backend`。
4. `armada-backend` 日志没有 Flyway 失败、XML 解析失败、数据库连接失败。
5. RDS 只新增或迁移 `armada` schema, 不影响旧 wheel schema。
6. 旧 wheel 入口 `http://65.2.123.53/` 和 `http://65.2.123.53:8081/` 不受影响。

`armada-protocol` 旁路部署完成后必须满足:

1. `protocol-layer.service` 仍 active, `8080` 仍可用。
2. `armada-protocol.service` active, `18081` 可用。
3. `/v1/health` 使用 armada 独立 API key 返回正常。
4. Redis key 只落在 `armada:` prefix。
5. Kafka 只写 armada 独立 topic。
6. MySQL 只写 `armada_protocol` schema。

## 8. 回滚

Web/API 回滚:

1. 保留远端上一版 jar 和 dist 的时间戳备份。
2. 发版失败时优先回滚 jar/dist 并重新 `docker compose up -d --build`。
3. 如果 Flyway 已执行成功, 不自动回滚数据库, 由对应 migration 的 rollback 方案单独处理。
4. 如果只是前端失败, 可只回滚 `wheel-saas-pure-web/dist` 并重建 nginx。

协议旁路回滚:

1. `sudo systemctl stop armada-protocol.service`。
2. armada API 配置切回现役协议层地址。
3. 不修改 `protocol-layer.service`。

## 9. 本次范围外

1. 不把 `armada-protocol` 与 Web/API 部署合并成一个脚本。
2. 不自动创建 RDS schema 或用户授权。
3. 不引入域名和 HTTPS。
4. 不迁移旧 wheel 数据。
5. 不清理旧测试服 Docker build cache。
6. 不做 `armada-protocol` 代码重构。

## 10. 实施顺序

1. 先实现 `armada/armada-deploy` Web/API 一键部署脚本。
2. 准备 RDS `armada` schema 和远端 `/home/app/armada-deploy/.env`。
3. 首次部署到 `65.2.123.53:18080` 并冒烟。
4. 单独实现 `armada-protocol` 旁路部署脚本或 runbook。
5. 在协议机启动 `armada-protocol.service:18081`。
6. armada API 切换协议地址并验证。
