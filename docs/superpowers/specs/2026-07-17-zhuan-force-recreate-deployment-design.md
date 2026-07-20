# Zhuan 测试部署强制重建设计

## 问题与证据

`sh armada-deploy/deploy-test.sh --zhuan` 已将本地 Zhuan 源码同步到默认测试环境并成功构建新镜像，但正在运行的 `whatsapp-android-zhuan` 容器仍引用旧镜像 ID。远端部署脚本通过 `bash -s` 的标准输入执行，而 `docker compose run` 的 `--interactive` 在测试机默认为 `true`。迁移容器因此会继续读取并吞掉标准输入中下一条主服务启动命令，导致该命令根本没有执行。

这会造成源码、镜像标签与实际运行二进制不一致。当前 `@all` 的 `nonJidMentions` 改动已经出现在远端源码和新镜像构建输入中，但测试请求仍由旧容器处理。

## 目标

- 每次 Zhuan 部署完成构建和迁移后，都让主服务使用本次新构建的镜像。
- 只对 `whatsapp-android-zhuan` 增加 `--force-recreate`；`redis-zhuan`、`callback-zhuan` 保持原有 Compose 启动语义，不删除 Redis volume。
- 保持现有同步、配置检查、数据库迁移和健康检查顺序不变。

## 方案

先把迁移命令从：

```bash
sudo docker compose run --rm whatsapp-android-zhuan /app/whatsapp-migrate -env prod
```

改为：

```bash
sudo docker compose run --rm --interactive=false whatsapp-android-zhuan /app/whatsapp-migrate -env prod
```

显式关闭迁移容器的 stdin 交互，保证远端 Bash 能继续读取下一条命令。然后将 Zhuan 远端部署流程最后一步从：

```bash
sudo docker compose up -d whatsapp-android-zhuan
```

改为：

```bash
sudo docker compose up -d --force-recreate whatsapp-android-zhuan
```

`--force-recreate` 只作用于命令指定的主服务。依赖服务仍由前面的 `docker compose up -d redis-zhuan callback-zhuan` 管理；本次不向依赖服务增加强制重建参数，但当 Compose 检测到依赖服务自身的镜像或配置变化时，仍可能按原有语义重建它们。

不采用全栈 `docker compose down/up`，因为它会扩大中断范围；不在本次引入动态镜像标签，因为这需要同步调整 Compose 配置与镜像清理策略，超出当前修复范围。

## 执行顺序与失败处理

远端流程保持为：Compose 配置校验、构建主镜像、确保依赖服务运行、执行迁移、强制重建主服务、轮询三个容器健康状态、检查 HTTP 接口。

任一步失败继续以非零状态终止。若强制重建或健康检查失败，部署脚本保留容器和日志供排查，不自动回滚数据库迁移。

## 测试与验收

1. 先扩展 `armada-deploy/deploy-test.test.sh`，要求迁移命令必须包含 `--interactive=false`，主服务启动命令必须包含 `--force-recreate`，并验证迁移仍在主服务重建之前。
2. 运行 shell 语法检查、部署脚本契约测试和 `--zhuan --dry-run`。
3. 执行默认测试环境的 `--zhuan -y` 部署。
4. 验证运行中 `whatsapp-android-zhuan` 容器的镜像 ID 与当前 Compose 镜像标签的镜像 ID 一致，且三个容器均为 `running/healthy`。
5. 由业务侧使用 `mentionAll=true` 在真实群聊发送一条 `@all` 消息，验证群成员收到所有人提醒；消息正文仍可包含字面量 `@all`，验收以底层 mention-all 语义生效为准。

## 变更范围

本次只修改 Armada 仓库中的 Zhuan 部署脚本、对应 shell 测试和设计/实施文档，不改动 Android Zhuan 的消息编码实现，也不改动 Baileys 协议层。
