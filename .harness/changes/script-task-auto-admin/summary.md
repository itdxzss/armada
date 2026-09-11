# 剧本任务自动管理员与创建页精简

- 日期：2026-09-11；分支：1.0.3-snapshot；状态：本地实现与验证完成，未提交、未部署。
- 来源：本次用户确认取消任务内编排、展示分组账号数量、下方预览；每群从在控管理员随机选择一个，已授权开始编码。

## 设计与影响

- 创建页选择启用剧本并复制内容快照；取消默认间隔编辑、发送项编排和管理员选号。编辑草稿继续展示已保存快照，保存时使用自动分配身份。
- 推手分组选项增加 accountCount，SQL 统计当前租户未删除账号，零账号组仍展示。
- 管理员候选来自目标群当前在控管理员/群主，须真实在群、在线且可发送，支持该剧本全部管理员消息；候选不限定推手分组。
- 每群所有自动管理员角色共用一个随机账号；推手仍按角色无放回匹配，管理员不能兼任推手。整体匹配成功后写入原 bindings_json，恢复及运行复核不重抽。
- 未启动的新保存草稿不接受手动账号；已保存且带固定管理员的旧任务按原身份执行，避免改变在途绑定。
- 缺管理员或任一群缺推手时整体启动失败且零发送；运行中原自动管理员失去资格则按现有机制暂停。
- 修改涉及 marketing 资格/保存校验、group 候选查询、account 分组选项及同级前端。沿用租户、任务所有者及菜单权限，无新增权限。
- 数据库结构、Redis、协议接口均无变更。回退须先停止新自动管理员任务，旧后端无法执行其未绑定步骤；不得让旧版本接管。

## 验证计划

- [x] 新增失败测试：旧 SQL 未选出跨分组管理员（实际只有账号 4，期望 1、2、4）；前端自动管理员表单与旧草稿解绑测试先失败后通过。
- [x] 资格逻辑：自动选管理员、跨群不同候选、多个管理员角色共用账号、管理员不能占用唯一推手、真实管理员身份、全部管理员消息能力、恢复不换号及撤权后阻塞。
- [x] H2 真实 Mapper：分组计数/零账号/软删除/租户边界；跨分组管理员与群主/退群/撤权/软删除/缺失上下文，XML 校验通过。
- [x] 后端相关回归 71 项，失败 0、错误 0、跳过 0，包含执行事务、暂停恢复、定义快照及分组选项调用方。
- [x] 前端表单 6 项、本地 Playwright 7 项全部通过；typecheck、相关 ESLint/Stylelint/Prettier、build 通过。

## 执行证据

- 后端使用本机 Java 17，向 Surefire 传入既有 Byte Buddy javaagent，解决默认 Java 23 在沙箱中无法自附加的问题；未修改依赖或生产运行参数。
- Maven：`mvn -q -DargLine=-javaagent:<本机 byte-buddy-agent-1.14.19.jar> -Dtest='Script*Test,GroupScriptCandidateMapperH2Test,AccountGroupMapperH2Test,ContactAccountOptionsControllerTest,ContactAccountOptionsServiceTest,HyperlinkStrategyAccountContextServiceTest,HyperlinkTaskQueryServiceTest' test`，退出码 0。报告在 `armada-api/target/surefire-reports`，日志 `/private/tmp/script-auto-admin-be-regression.log`。
- 前端：`node --import tsx --test src/views/task/script-marketing/form.test.ts`；`npm run typecheck`；相关文件本机 `node_modules/.bin/eslint`、`stylelint`、`prettier --check`；`npm run build`，退出码均为 0。
- 浏览器：`CHOKIDAR_USEPOLLING=true PLAYWRIGHT_NO_COPY_PROMPT=1 node_modules/.bin/playwright test --config playwright.script-marketing.config.ts`，7 passed，38.7 秒，退出码 0。仅本地 API 夹具，未请求真实业务环境。
- 已人工查看新建抽屉截图：分组计数、下方对话预览、固定保存按钮均可见，无编辑器和管理员选号。
- 复核：沿用 Service 跨域边界、真实 SQL、原角色映射和租户插件；旧在途固定管理员从步骤字段识别并继续按原账号执行；四个其他业务测试仅因共享分组选项新增字段同步构造参数。

## 部署

本次仅本地实现与验证，未部署。
