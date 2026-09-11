# 素材库增加 PNG 支持

- 日期：2026-09-11
- 需求来源：用户要求“我们要增加 png 的”。
- 状态：代码及本地验证完成；尚未指定部署环境，未部署。

## 设计与影响

- 素材库上传及素材选择器接受 JPG/JPEG/PNG；扩展名、MIME 和内容签名须匹配，后端继续真实解码图片，单张上限仍为 500KB，每批仍最多 100 张。
- PNG 按原始字节及 `image/png` 保存，保留透明度，不转码；复用 `marketing_template_file` 的现有内容、MIME、尺寸字段。
- 选择器 SQL 将 PNG 纳入可选素材；养群剧本/任务绑定与超链模板/任务绑定统一使用现有素材图片校验器。
- 前端更新两个上传入口及素材库、选择器、模板提示；Web 协议命令已透传 MIME 和图片内容，无需协议代码修改。
- 无 API 结构、数据库结构、Redis、租户归属、权限或状态流转变更。查询仍使用现有租户插件与软删过滤。

## 验证

- 新增前端 PNG 测试先失败：PNG 被 JPEG 限制拒绝（2 失败、1 通过）。
- 新增后端测试先失败：上传校验、模板绑定拒绝 PNG，H2 选择器遗漏 PNG（25 用例，1 失败、2 错误）。
- 后端：`mvn -o -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest='ResourceAsset*Test,HyperlinkMessageContentValidatorTest,MarketingTemplateFileServiceImplTest,MarketingTemplateFileLockContractTest,ScriptMarketingImageContentTest' test`：37 用例通过，0 失败/错误/跳过。覆盖 PNG 真实解码、伪装/截断/缺失内容/超限拒绝、原字节保存、两类模板图片槽绑定、剧本 MIME 透传；其中真实 Mapper XML 的 H2 用例 8 个，包含 PNG/JPEG 筛选、500KB 边界、租户及软删约束。
- Mapper XML：`xmllint --noout src/main/resources/mapper/marketing/MarketingTemplateFileMapper.xml` 通过。
- 前端：`node --import tsx --import ./src/api/__tests__/node-test-alias.mjs --test src/views/hyperlink/**/*.test.ts src/api/resource-asset.test.ts`：134 用例通过，0 失败/跳过。
- 前端现有依赖执行 `tsc --noEmit`、`vue-tsc --noEmit --skipLibCheck`、改动文件 ESLint/Stylelint 及 `NODE_OPTIONS=--max-old-space-size=8192 ./node_modules/.bin/vite build` 全部通过，构建耗时 18.41 秒。两仓 `git diff --check` 通过。
- Web 协议：`node --experimental-vm-modules ./node_modules/.bin/jest --runInBand --runTestsByPath src/commands/worker-consumer.test.ts src/messages/card-content.test.ts src/routes/messages.link-button-card.test.ts`：3 suites、46 用例通过；包含 PNG 媒体和卡片场景。
- 本机执行差异：默认 pnpm 触发自动安装并因无 TTY 退出，改用已有 `node_modules/.bin`；API 测试使用仓库已有 alias loader，避免 Node 加载 CSS；Mockito 动态 attach 受限，最终用已缓存 Byte Buddy 启动 agent 执行，无需安装或修改依赖。
- 静态复核：PNG 原图不转码；前端两处上传均使用同一校验器，后端两处绑定复用同一校验器；选择器分页/count 使用同一过滤条件。未更改协议代码、权限、租户隔离或其他会话在途文件。
- 发布前专家评审：已核对完整改动、上传/绑定/组包调用链和测试证据，无阻断发现；生产仍经 Service/Mapper 读取真实数据，保留事务、租户插件、软删和大小限制。远程上传与收件效果须在目标环境另行验证。
- 未执行：远程环境上传、真实账号发送和收件验证。这些不属于本地测试通过的结论。

## 部署与回滚

- 尚未提交、推送或部署；部署需同时包含前端和后端。
- 回滚只还原本变更的校验、筛选和提示代码，无数据库回滚。回退后已上传的 PNG 字节仍保留，但会重新受到 JPEG 绑定限制；部署回退前应评估已有 PNG 引用。
