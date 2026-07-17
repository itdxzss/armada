# 协议层双环境路由与 Redis 连接复核记录

> 状态：待处理；问题已经在第一、第二套测试环境复现并完成根因定位
>
> 复核日期：2026-07-17
>
> 工作分支：`1.0.1-snapshot`
>
> 涉及仓库：`armada`、`armada-protocol`
>
> 本文用途：记录双环境部署复核中发现的协议路由配置歧义和 Redis 阻塞连接复用问题；本次仅记录，不修改功能代码、不重启服务、不操作账号

## 1. 复核结论

两套环境的 Armada 后端均只访问协议 master `:8080`，再由 master 根据注册表把请求转发给同机 worker。第二套 worker 已正常注册到 master，并非注册失败；但两套环境的注册地址、部署文档和 Armada 运维探针没有采用同一套拓扑口径。

两套环境还共同存在一个代码级问题：worker 使用同一个 Registry Redis 连接执行 `XREADGROUP BLOCK 4000` 和普通注册中心命令，导致 `/readyz`、心跳及其他同连接命令最多排队约 4 秒。该问题不是第二套部署差异，而是当前协议层连接装配方式造成的。

## 2. I-01 master-only 实际拓扑与 `PUBLIC_ENDPOINT` 语义不一致

状态：`OPEN`

严重度：中。当前 master 本机转发可用，但直连语义、运维探针和部署文档存在歧义。

### 当前行为

- 第一套 worker 注册为 `http://127.0.0.1:8081-8084`。
- 第二套 worker 注册为 `http://0.0.0.0:8081-8084`。
- 两套应用机访问协议 master `:8080` 均正常，访问协议机私网 worker `:8081-8084` 均超时。
- master 与 worker 同机，因此第一套通过 loopback 转发；第二套在当前 Linux 主机上也能连接本机 `0.0.0.0`，所以主链路目前可用。
- 第一套注册表有 4 个在线 worker，复核时 worker-4 `currentLoad=202`，证明注册、owner 分配和 master 转发正在工作。
- 第一套 Armada 配置的 worker readiness 公网地址当前全部超时，但正常业务 base URL 仍走私网 master，因此账号业务没有随之中断。

### 根因

监听地址、注册地址和业务访问拓扑没有分层：

1. `HTTP_HOST` 表示 Fastify 监听地址。
2. `PUBLIC_ENDPOINT` 原设计和部署文档将其定义为功能层可访问的 worker 地址。
3. 当前实际业务流量统一进入 master，注册地址实际上被用于 master 到同机 worker 的内部转发。
4. 第二套 PM2 配置又使用 `HTTP_HOST` 拼接 `PUBLIC_ENDPOINT`，因此 `HTTP_HOST=0.0.0.0` 时注册出 `0.0.0.0`。

涉及位置：

- `../armada-protocol/protocol-layer/deploy/pm2.config.cjs:25`
- `../armada-protocol/protocol-layer/deploy/pm2.config.cjs:108`
- `../armada-protocol/protocol-layer/docs/DEPLOYMENT.md:135`
- `../armada-protocol/protocol-layer/docs/BUSINESS-INTEGRATION.md:264`

### 待确认的修复口径

方案 A：固定 master gateway 模式。

- PM2 单机 worker 统一注册 `127.0.0.1:8081-8084`。
- Armada 只配置 master 地址，不再配置不可达的 worker readiness URL。
- 更新协议部署和业务接入文档，明确 worker endpoint 仅供 master 内部使用。

方案 B：保留 Armada 直连 worker。

- 独立配置 `PUBLIC_HOST` 或每个 worker 的 `PUBLIC_ENDPOINT`。
- 注册协议机真实私网 IP 或内部域名。
- 安全组仅允许应用机安全组访问 `8081-8084`，并增加跨机连通性验收。

### 关闭条件

- 两套环境采用同一种明确拓扑，注册表不再出现 `0.0.0.0`。
- Armada 中配置的所有 master/worker readiness URL 均能从实际调用方访问。
- 账号注册、owner 分配、master 转发和批量命令分别完成一次端到端验证。

## 3. I-02 worker 阻塞式 Redis Stream 消费与注册中心共用连接

状态：`OPEN`

严重度：高。worker readiness 和同连接注册中心命令会被稳定阻塞最多约 4 秒。

### 环境证据

- 两套 master `/readyz` 均稳定约 4-5ms。
- 第一套 worker-1 首次约 0.45s，随后连续约 4.02s；worker-2 同样复现。
- 第二套 worker-3 连续采样约 3.07s、4.02s、4.02s、4.02s、4.02s。
- 所有样本最终返回 HTTP 200，进程保持 online，`/livez` 正常，event-loop lag 约 0-1ms。
- 该现象不是 Node 事件循环卡死或 Redis 整体不可用，而是单连接命令排队。

### 根因

- `readWorkerCommandStreamOnce()` 默认执行 `XREADGROUP BLOCK 4000`。
- `server.ts` 将 `registryRedis` 直接传给 worker command stream loop。
- 同一个 `registryRedis` 还用于 registry、heartbeat、owner/assignment、group join state 和 `/readyz` 的 `PING`。
- Redis 单连接上的命令串行执行；阻塞读取占住连接时，后续命令只能等待本轮最多 4 秒的 BLOCK 结束。
- readiness 第一次请求可能只等待当前 BLOCK 的尾段，紧接着再次请求会与下一轮 BLOCK 对齐，因此连续采样稳定接近 4 秒。

涉及位置：

- `../armada-protocol/protocol-layer/src/server.ts:430`
- `../armada-protocol/protocol-layer/src/server.ts:462`
- `../armada-protocol/protocol-layer/src/commands/worker-stream-consumer.ts:45`
- `../armada-protocol/protocol-layer/src/commands/worker-stream-consumer.ts:183`

### 最小修复方向

1. 为 worker 阻塞式 Stream 消费创建独立 Redis 连接，例如基于 Registry Redis 配置执行 `duplicate()`，只给 `XREADGROUP` 使用。
2. registry、heartbeat、owner/assignment、group join state 和 readiness 保持使用非阻塞命令连接。
3. shutdown 时同时关闭独立消费连接，避免连接泄漏。
4. 增加回归测试：阻塞消费进行中，registry `PING`、heartbeat 和 `resolveOwner` 不应等待 BLOCK 超时。

### 关闭条件

- 空 Stream 下连续请求各 worker `/readyz`，不再呈现固定约 4 秒延迟。
- 阻塞消费期间 heartbeat age 正常，owner 分配和查询延迟不受 `BLOCK 4000` 影响。
- 第一、第二套环境分别复测 master 和 4 个 worker，并保存延迟样本。

## 4. 本次未执行事项

- 未决定 I-01 最终采用 master-only 还是 Armada 直连 worker。
- 未修改 Armada 或协议层功能代码。
- 未重启、reload 或重新部署任何服务。5 
- 未操作 WhatsApp 账号，也未执行真实消息、上线或群任务。
