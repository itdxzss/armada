# 拉手替换后退出执行行

## 目标与事实

test1 #206 的执行行 486 中，初选拉手在联系人阶段掉线，系统选入替代账号；旧角色后来恢复可用，却没有进群动作，仍阻挡人工补充。用户确认在主仓库修复。

## 设计与影响

- 自动选号及人工补充成功后，在同一事务内将对应旧角色标记 REMOVED、原因 PULLER_REPLACED，并释放占位；失败回滚新旧角色变更。
- 真实进群请求仍在途或结果未知时保留名额，不能因暂时掉线重复补人。
- 已替换角色不再自动恢复；未被替换的角色恢复后，缺少进群事实时返回联系人/进群检查点。
- 补充 options 与提交校验使用同一缺口口径。页面显示可补充数量以及已替换、不占用状态。
- 数据库：复用 availability_status、unavailable_reason_code、released_at，无 Flyway 变更。
- API：保持补充请求不变，当前角色返回补充不可用原因码；missingPullerCount 表示当前可补充数量。
- Redis、协议命令契约、租户与权限边界不变。

## 验证

- Java 17 + 启动时加载 Byte Buddy agent，避免本机 Mockito 动态 attach 限制；未修改项目依赖或测试运行配置。
- 首次回归：6 个用例中 4 个按预期失败（退出状态、恢复阶段、展示缺口、人工替换），2 个保护用例通过。
- 后端定向验证共 108 项通过、0 失败、0 错误、0 跳过：联系人与自动替换 16、补充服务 11、恢复 13、补充进群 5、邀请进群 6、角色 Mapper 22、账号状态事件 3、拉手选择 11、Controller 15、协议回调 6。
- H2 加载真实 Mapper/租户插件/事务管理器，覆盖旧角色退出、未替换角色回到进群前置阶段、失败回滚、未知进群保留名额、迟到离线事件不复活旧角色、跨租户与并发状态变化拒绝替换。
- 前端 API、抽屉、composable 共 18 项通过；TypeScript、vue-tsc、定向 ESLint 及 Vite production build 通过。
- Mapper XML 校验及 diff 空白检查通过；人工检查了候选占位、执行行乐观锁和新旧账号切换的事务边界。
- 构建输出位于 `/private/tmp/puller-replacement-web-dist`，未覆盖主仓库发布目录。
- 主仓库存在其他任务的账号恢复修改；只增补本次相关代码，不回退他人修改。

验证命令：

```bash
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -q \
  -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar \
  -Dtest='PullTaskManagerPullerContactTransactionIntegrationTest,PullTaskPullerSupplementServiceTest,PullTaskResourceRecoveryTransactionIntegrationTest,PullTaskSupplementPullerTransactionIntegrationTest,PullTaskPullerInviteTransactionIntegrationTest,PullTaskGroupAccountMapperInMemoryTest,PullTaskPullerAccountStateServiceImplTest,PullTaskStickyPullerTransactionServiceTest,PullTaskStandardControllerTest,PullTaskProtocolResultCallbackServiceImplTest' test
node --import ./src/api/__tests__/node-test-alias.mjs --test --experimental-strip-types \
  src/views/task/pull-task/components/PullTaskPullerSupplementDrawer.test.ts \
  src/views/task/pull-task/composables/usePullTaskPullerSupplement.test.ts src/api/pull-task.test.ts
```

命令中的后端类分两批执行，前端使用已安装的本地工具二进制；pnpm 11 的 exec 会尝试重装 node_modules，因此未继续该自动安装。

## 部署与回滚

- 本次仅代码调整与本地验证，尚未部署。
- 未重启或恢复已经人工结束的 #206，未执行真实账号进群/拉人验收。
- 回滚仅撤销本任务文件中的对应补丁；不回退其他任务工作。已标记替换的角色历史不应批量恢复占位。
