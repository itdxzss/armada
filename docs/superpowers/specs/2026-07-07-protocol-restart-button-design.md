# 协议重启按钮设计

日期：2026-07-07
范围：`armada-api`、`wheel-saas-pure-web`

## 背景

当前协议层在业务执行前容易被打崩，需要一个简单的人工按钮，先把协议进程重启一遍，释放 Node 进程内存和运行时状态。账号下线、上线继续使用现有账号页批量操作，不放进本按钮自动串联。

协议层测试部署使用 PM2 多进程：

- `protocol-master`：master 进程，HTTP 端口 8080。
- `protocol-worker-1` 到 `protocol-worker-4`：4 个 worker 进程，HTTP 端口 8081 到 8084。

协议层现有 `/readyz` 可以判断 Redis、Kafka、runtime store、publisher 等依赖是否已恢复。

## 目标

在 Armada 前端账号页提供一个“重启协议”按钮。点击后后端执行固定 PM2 重启命令，并等待 master 与 4 个 worker 全部 ready。成功后提示用户协议已重启；失败时提示具体失败阶段。

## 非目标

- 不自动下线账号。
- 不自动上线账号。
- 不新增任意命令执行接口。
- 不修改协议层进程 shutdown 逻辑。
- 不处理多环境权限模型，先按当前简单测试使用落地。

## 后端设计

新增后端端点：

```http
POST /api/protocol/restart
```

Controller 只做请求转发，业务放在 service：

```java
public interface ProtocolProcessRestartService {
    ProtocolRestartVO restart();
}
```

`ProtocolRestartVO` 返回：

- `success`：是否成功。
- `command`：脱敏后的固定动作描述，例如 `pm2 restart protocol-master protocol-worker-1 protocol-worker-2 protocol-worker-3 protocol-worker-4 --update-env`。
- `startedAt`、`finishedAt`、`elapsedMs`。
- `processes`：每个进程的 ready 检查结果。
- `message`：成功或失败说明。

服务执行固定命令：

```bash
pm2 restart protocol-master protocol-worker-1 protocol-worker-2 protocol-worker-3 protocol-worker-4 --update-env
```

命令参数由后端代码或配置生成，前端不能传入命令、进程名或 shell 片段。实现使用 `ProcessBuilder` 参数数组，不使用拼接 shell 字符串。

命令成功退出后轮询以下 readiness 地址：

```text
http://127.0.0.1:8080/readyz
http://127.0.0.1:8081/readyz
http://127.0.0.1:8082/readyz
http://127.0.0.1:8083/readyz
http://127.0.0.1:8084/readyz
```

每个地址在总超时时间内重试，全部返回 2xx 才算成功。若 PM2 命令非 0 退出，或任一进程 ready 超时，接口仍返回 `ApiResponse.ok(data)`，但 `data.success=false`，并在 `data.message` 写清失败阶段。只有入参、配置缺失、未预期运行时异常才走统一异常响应。

## 配置

新增 `armada.protocol-restart` 配置，默认值直接适配当前 PM2 部署：

```yaml
armada:
  protocol-restart:
    pm2-bin: pm2
    process-names:
      - protocol-master
      - protocol-worker-1
      - protocol-worker-2
      - protocol-worker-3
      - protocol-worker-4
    ready-urls:
      - http://127.0.0.1:8080/readyz
      - http://127.0.0.1:8081/readyz
      - http://127.0.0.1:8082/readyz
      - http://127.0.0.1:8083/readyz
      - http://127.0.0.1:8084/readyz
    command-timeout-ms: 30000
    ready-timeout-ms: 60000
    ready-poll-interval-ms: 1000
```

这些配置只允许定义固定 PM2 binary、固定进程名和固定健康检查地址，不从请求体接收动态值。

## 前端设计

在 `wheel-saas-pure-web` 的账号列表工具栏新增独立按钮：

```text
重启协议
```

交互：

1. 用户点击按钮。
2. 弹出确认框，提示“会重启协议 master 和 4 个 worker，当前在线连接会断开；账号下线/上线请继续使用现有批量操作。”
3. 用户确认后调用 `POST /api/protocol/restart`。
4. 请求期间按钮进入 loading，避免重复点击。
5. 成功时提示“协议已重启”。
6. 失败时显示后端 message。

API 封装新增到 `src/api/account.ts` 或独立 `src/api/protocol.ts`。为了保持职责清晰，推荐独立 `src/api/protocol.ts`。

## 错误处理

- PM2 命令执行失败：返回失败 message，包含退出码和截断后的 stderr，不暴露环境变量。
- PM2 命令超时：销毁进程，返回超时失败。
- ready 检查失败：返回未 ready 的 URL 或进程名。
- 后端接口异常：前端按现有 `apiErrorMessage` 展示。

## 测试

后端：

- `ProtocolRestartPropertiesTest`：验证配置绑定和默认值。
- `ProtocolProcessRestartServiceImplTest`：验证固定命令参数、PM2 成功后轮询 ready、ready 超时返回失败、命令失败返回失败。
- `ProtocolProcessControllerTest`：验证 `POST /api/protocol/restart` 委托 service 并返回 `ApiResponse`。

前端：

- `src/api/protocol.test.ts`：验证调用 `POST /api/protocol/restart`。
- 账号页 composable 或组件测试：验证点击确认后调用重启 API、loading 状态、成功/失败提示。

## 验收

本地或测试服部署后：

1. 在账号列表点击“重启协议”。
2. 后端执行固定 PM2 重启命令。
3. 后端等待 `8080..8084 /readyz` 全部成功。
4. 前端提示协议已重启。
5. 用户可以继续使用现有“离线”“登录”批量操作。
