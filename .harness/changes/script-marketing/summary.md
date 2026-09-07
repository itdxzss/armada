# 剧本营销任务

状态：首版本地实现与二次复核完成，用户已授权四仓提交推送，并部署第一套环境 test1；真实群验收尚未运行。

## 范围
独立任务列表、配置与详情；管理员和多个推手按步骤发送；失败继续、等待、暂停/人工接管与恢复不重复发送。旧营销页面和旧任务表不改。

## 数据设计
- script_marketing_task 是新任务聚合，配置步骤 JSON 固定在任务启动时；旧任务只支持单模板和轮次，不能表达此配置。
- script_marketing_group 保存每个目标群独立进度、下次时间及暂停剩余等待，不能由任务级轮次表示。
- script_marketing_send_record 保存每群每项唯一发送事实及原命令 ID；旧 attempt 属于旧任务，不能共用。
- 账号和素材只引用现有数据，不增加剧本库、角色库、版本或通用工作流。

## 实施清单
- [x] 三表 Flyway / Mapper / H2 测试
- [x] 创建、修改、分页、详情、开始/暂停/恢复/关闭
- [x] 单项执行、结果与超时收敛、暂停原命令保护
- [x] Web / Android 新消息来源契约
- [x] 独立前端页面与权限菜单
- [x] 本地测试 / 文档

## API 与权限
/api/script-marketing-tasks，权限 tenant:script_marketing:view/create/edit/operate。任务隔离到租户及创建用户，账号和素材沿用现有租户资源权限。Redis 无新增。

## 回滚
停止新任务并隐藏新菜单，保留三表审计数据。回滚 SQL 只移除新菜单；不自动删除任务数据。无部署、无真库操作、无真实消息发送。


## 验证证据

- 同步到主仓库后：剧本专项、消息回调、outbox 和素材 Mapper 共 74 项通过（0 失败、0 错误、0 跳过）；同步时四仓代码与 worktree 内容一致（交付路径说明已更新），diff 检查通过，HEAD 与暂存区未变化。
- 后端相关回归：165 项通过（普通营销、素材、消息组合、outbox、消息回调与新功能）。
- 后续边界补充：剧本专项最终 17 项通过；涵盖双调度竞争、双回调竞争、失败继续、末项失败完成、暂停持有/恢复同一命令、在途结果、超时取消/未知、迟到回调、事务回滚、回滚后失败收敛、租户与创建人边界，以及三表和菜单迁移。
- Web 协议：新来源、私聊旧来源和通用 worker 共 47 项通过；TypeScript build 通过。
- Android：go vet ./...、go build ./... 通过；go test ./... 中 internal/armada 通过。全量仍有 pkg/noise 的 8 个历史用例失败，在原分支复现相同失败，非本功能修改范围。
- 前端：typecheck、局部 ESLint、build 通过；表单行为 3 项、本地完整页面交互 1 项通过。浏览器夹具拦截 /api/ 请求，没有调用真实环境；验证了默认两项、追加推手、排序及保存后的账号/消息顺序。
- Mapper XML 解析、Flyway 版本唯一性与 API 文档生成测试通过。
- MySQL 真实环境迁移、真实账号与群消息发送均 NOT_RUN。H2 执行迁移和真实 Mapper，JSON 字符串和 JSON_CONTAINS 使用测试方言适配，不能替代 InnoDB 及实际协议验收。
- mvn test 的旧 DbTest 会尝试连接数据库，已中止该部分；后续相关回归显式排除 DbTest/MySqlTest，只在 H2 中验证数据库行为。

## 关键复核

- 所有发送记录拥有 (tenant_id, group_id, step_index) 唯一键和唯一 command_id；不生成自动重试记录。
- 业务意图和 outbox 同事务，调度失败回滚后仅在原意图确实不存在且当前步骤未改变时记录失败；原命令存在则继续跟踪。
- 回调先在事务外解析归属，再在新事务中锁任务并读取状态，避免 MySQL REPEATABLE READ 的旧快照重复覆盖进度。
- pause 控制只覆盖 outbox PENDING/LOCKED；DISPATCHING 及协议内消息仍跟踪原结果。恢复仅激活 SCRIPT_PAUSED 标记的同一命令，不重放已发送命令。
- 素材删除保护计入新任务 JSON 内的引用。同一任务多次引用同一素材只计一处。
- 旧营销前端文件、旧营销任务表均无修改；共享消息契约新增可空关联和来源，旧 Java 调用点仅补空参数。

## 2026-09-07 主仓库二次复核

- 修正 Android 按钮能力预检缺失：保存和启动时拒绝多按钮、复制或快捷回复按钮；一个链接按钮仍可用，Web 原有能力不受影响。回归测试先复现“未抛校验异常”，修正后通过。
- 修正前端详情与发送记录的响应乱序：关闭或切换任务后忽略旧请求结果，加载失败清空旧内容。两项浏览器用例分别复现详情、记录串任务，修正后通过。
- 后端新功能、消息结果、outbox、素材和 Web/Android 发送适配器共 96 项通过（0 失败、0 错误、0 跳过）。日志：/tmp/script-review-backend-green.log。
- 前端 3 项表单测试、3 项浏览器测试、typecheck、局部 ESLint、build 通过；四仓 diff 检查通过，未暂存或提交。浏览器业务 API 均被夹具拦截。
- 本轮修正仅在主仓库工作目录中，原 worktree 是修正前备份；未新增表或变更发送状态机。
- 未运行真实 MySQL 迁移或真实群发送；Android 底层代码本轮未改，之前的 8 个 Noise 测试失败未处理，未再次运行 Android 全量测试。

## 复现命令

2026-09-07 交付前验证：后端连同当前分支入库、查询优化回归共 227 项通过；Web 协议 47 项通过；test1 部署脚本检查通过。生产离线包检查因已有 inspect-production-host.sh 缺失而失败，本次 test1 部署不走该路径。新增 V180 与当前 V179_1 无版本冲突。

后端：mvn -f armada-api/pom.xml -Dtest='ScriptMarketing*Test' test。
当前沙箱不能动态 attach Mockito，实际运行加 -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar。

前端：npm run typecheck；npm run build；node --import tsx --test src/views/task/script-marketing/form.test.ts；npx playwright test --config playwright.script-marketing.config.ts（使用本机 Chrome，服务只监听 127.0.0.1:5194）。

## 交付与上线边界

代码已从独立 worktree 同步到 /Users/daishuaishuai/IdeaProjects 下的 armada、wheel-saas-pure-web、armada-protocol 和 whatsapp-server-feature-android-zhuan。同步共 65 个文件，保留各主仓库原有修改和暂存区；原 worktree 保留作备份。
同步时后端主仓库基于 f173d13d，其余三仓与 worktree 基线一致；功能代码无冲突，V180 无版本冲突。先使两个协议支持 script_marketing 来源，再启用新后端与前端。新任务先保存草稿，显式启动才发送。

.harness/wiki/数据模型.md 的生成器要求迁移后真库 information_schema TSV；没有确认环境或真库输入，本地未手改或伪造该生成物。部署后再生成。
