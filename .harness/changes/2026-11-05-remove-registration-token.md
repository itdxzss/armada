# 变更记录：移除新增注册租户令牌

- 日期 / 分支：2026-11-05 / codex/remove-registration-token-20261105，目标 1.0.3-snapshot。
- 需求来源：用户明确要求删除本次注册新增的逐租户令牌链路，并合入 1.0.3。
- 状态：代码实现与验证完成；未重新部署。

## 目标

Armada 通过受控内部 HTTP 调用 Go 注册接口，不再维护逐租户 Bearer、摘要及权限映射配置。

## 任务清单

- [x] 删除 Armada token 属性、请求头与启动依赖，保留个人/商业 profile 映射。
- [x] 删除 Go principals、摘要匹配和读写权限映射，三个注册接口改用必填 X-Tenant-Id。
- [x] Go HTTP 集成 operator 固定 armada，继续按 tenant_id/operator_id 读取设备模板及事务；缺失、歧义、非法租户 ID 拒绝。
- [x] 更新接口合同、样例配置与回归测试，验证无令牌调用、跨租户资源隔离、幂等。
- [x] 完成合入前评审；集成目标为两仓 1.0.3-snapshot，分支落点以 Git 记录为准。

## 设计与边界

- X-Tenant-Id 是正整数租户 ID，来源为 Armada 登录上下文或已持久化任务。前端不能指定覆盖；后台任务沿用记录的租户。
- Go 信任受控 Armada 调用方传入的租户上下文。该头不是认证机制，网络访问边界必须限制注册接口仅供 Armada 使用；源码 Compose 的全网卡端口绑定不等同于已验证网络隔离。本次代码合并不改变测试服务器的防火墙或运行配置。
- 保留注册数据加密、验证码 HMAC、幂等、代理固定、状态版本检查及业务功能开关，它们与接口调用令牌无关。
- 无数据库结构或 Redis 变更；已有两套 profile 的 operator_id=armada 可继续使用。其他 operator 归属的既有数据不会自动改写。
- Armada/Go 需成对更新；不保留旧令牌兼容分支。回滚需同时回退两仓并恢复旧部署配置。
- 基于远端最新个人/商业注册提交 e77b89b78 / d4be9c7，保留其业务类型、版本和 profile 选择。

## 验证

- 红灯：旧实现下 Java 22 项测试中 5 项失败（无令牌配置与 HTTP 头），Go 真实工作流无令牌请求返回 401。
- Java/JDK 21：`mvn -q '-Dtest=GoRegistration*Test,SmsRegistration*Test,SmsActivationServiceTest,SmsActivationMapperH2Test,IpProxyRegistrationMigrationH2Test' test`，8 类 71 项，0 失败/错误/跳过。含真实 H2 Mapper、注册 worker 租户恢复、个人/商业 profile 与本机 HTTP 适配。
- `python3 .harness/wiki/test_api_docs.py`：1 项通过，生成 76 个 Controller / 406 个端点。
- Go 1.26.5：改动文件 `gofmt`、`go vet ./...`、`go build ./...` 均通过。
- `go test ./...`：52 个包通过，2 个包失败。`pkg/noise` 的 8 项旧失败在原始 d4be9c7 基线复现，包含 vectors.txt 缺失及旧向量断言；`internal/service/utils/promise` 出现 TestNewTimeOut 结束后的异步 t.Log panic，该目录及依赖未改，基线单次及 -count=10 复跑均通过，时序现象未稳定复现。未声称全仓测试全绿。
- `go test -race -count=1 ./api/middleware ./api/controller ./api/router ./cmd/server ./internal/registration ./doc`：6 包全部通过，包含真实 Workflow + SQLite 的无令牌三接口、跨租户模板/读取/重发/验码拒绝及同键重放不重复发送。
- Swagger JSON、运行时 Go 文档与 YAML 合同一致；结构比较确认只修改三个注册路由，其他 API 未变。两仓 `git diff --check` 通过。
- 日志位于本机 `/private/tmp/remove-registration-token-20261105/`，未调用真实 Go、WhatsApp 或短信供应商。

## 合并前评审

已检查完整差异、调用方、启动配置、数据归属与测试证据。未发现本次改动的阻断问题。旧调用令牌路径和兼容参数已移除；Armada 登录权限及持久化 tenant_id 未改，Go 按租户和固定集成归属读取数据。无 Mapper SQL、表结构、协议原生 token 或加密算法变更。剩余边界：全量旧测试失败、网络隔离未实机复核、未做真实发码验收。

## 部署

本次目标为代码合入；Go 配套提交 `74820ff`。尚未重新部署或调用真实发码服务。
