# 通讯录任务列表与批量删除

- 日期：2026-09-09；主仓库 armada、wheel-saas-pure-web；分支 1.0.3-snapshot。
- 需求：用户确认列表增加 ID、任务名称、本页批量删除，并明确在主仓库实施。
- 状态：本地实现及验证完成，未部署；保留既有协议诊断及并发会话的云端联系人相关修改。

## 设计与影响
- 列表字段复用 id/name；增加选择列、确认框、已选数量及删除后分页回退。
- POST /api/contact-tasks/batch-delete，JSON {ids:[...]}，data 返回实际删除数量。
- 最多 200 个 ID，去重升序锁定；仅状态 0/2/4 允许，运行中/暂停先停止。
- Service 事务、真实租户插件及条件 SQL 保证整批成功或回滚。
- 复用 deleted_at，保留账号和收件人历史；不新增任务字段，不修改 Redis 或协议层。
- V185 仅增加 tenant:contact_task:delete 菜单按钮权限，普通角色不自动授权。
- 已发出的协议命令不因软删除撤回，迟到回执仍可归档到保留的明细。

## 验证
- 前端新列/删除入口测试先红；后端新 H2 测试先因缺少 batchDelete 方法编译失败。
- 后端：使用本机 Maven 3.9.16 / Java 21（项目 Java 17 编译目标），Mockito 显式加载本地 byte-buddy-agent 1.14.19。
  - `mvn -q -DargLine=-javaagent:<本机 byte-buddy-agent 路径> '-Dtest=ContactTask*Test,ContactAccountOptionsControllerTest,ContactMenuRbacMigrationSqlTest' test`：151 测试，0 失败/错误/跳过。
  - 最终迁移时间表达式调整后，`-Dtest=ContactTaskBatchDeleteH2Test,ContactAccountOptionsControllerTest,ContactTaskServiceImplTest`：36 测试，0 失败/错误/跳过，退出码 0。
  - H2 的 8 项新增测试覆盖软删可见性、明细保留、整批拒绝、租户边界、参数上限、真实事务回滚、双向调度锁竞争和权限迁移幂等。
  - H2 不支持 MySQL UNIX_TIMESTAMP；迁移测试仅将时间表达式替换为固定毫秒值，原始 INSERT/关联/唯一约束在 H2 MySQL 模式实际执行。未连接共享 MySQL；H2 不替代 InnoDB 特有锁语义验证。
- 前端：132 项相关 Node 测试通过；tsc、vue-tsc、定向 ESLint、Prettier、生产 Vite build（输出 /tmp/contact-task-delete-build）通过。
  - Node 测试使用已有 `src/api/__tests__/node-test-loader.mjs`；为避免 tsx 的生产 alias 抢先加载真实 http/CSS，TSX_TSCONFIG_PATH 指向仅含 target/module/moduleResolution、不含 paths 的临时 JSON。
  - 命令：`TSX_TSCONFIG_PATH=/tmp/contact-task-tsconfig.json node --import tsx --import 'data:text/javascript,import { register } from "node:module"; import { pathToFileURL } from "node:url"; register("./src/api/__tests__/node-test-loader.mjs", pathToFileURL("./"));' --test src/api/contact-task.test.ts 'src/views/contact/hyperlink/**/*.test.ts' src/views/contact/hyperlink/ContactHyperlinkIndex.test.ts`。
- 浏览器：新增 `e2e/contact-task-delete-local.spec.ts`，3 项通过，另复核取消后复选框可继续操作通过；所有 API 由本地测试夹具拦截。
  - `ARMADA_E2E_BASE_URL=http://127.0.0.1:8849 ARMADA_E2E_BROWSER_CHANNEL=chrome playwright test e2e/contact-task-delete-local.spec.ts --reporter=line --output=/tmp/contact-delete-playwright`。
  - 对生产构建提供本地静态服务；Vite dev 因文件监听限制未能启动，改用静态构建预览；隔离 Chrome 与本机端口经沙箱授权启动。
  - 检查 1920×1080 截图 `/tmp/contact-task-delete-list.png`；新增列、长名称省略、批量按钮和固定操作列正常，宽表支持横向滚动。
- `xmllint --noout ContactFriendTaskMapper.xml` 与两仓 `git diff --check` 通过。
- 额外竞态保护：编辑 UPDATE 未命中（任务已删）时返回业务冲突，不继续展开账号/收件人。

## 部署与回滚
- 用户追加授权：提交、推送，并部署到第一套测试环境 test1；范围为两仓 1.0.3-snapshot 上的本次功能。
- 发布时执行新增 Flyway 迁移并刷新权限；普通角色由管理员显式授权。
- 回滚应用代码可隐藏入口并停用新接口；保留权限节点和已软删标记，不自动恢复任务。

## 发布前评审
- 按 expert-reviewer 检查本次完整 diff、调用方、真实事务/租户测试及权限迁移，无阻断项。
- 批量删除锁顺序固定，状态校验在行锁后执行；软删条件及租户插件阻止越权更新。
- 现有主仓库的云端联系人修复属于其他会话，不纳入本次提交；部署从已提交源码构建。
- 剩余验证：发布后检查 Flyway V185、运行制品哈希、权限节点和实际页面；不删除已有业务任务作为验收。
