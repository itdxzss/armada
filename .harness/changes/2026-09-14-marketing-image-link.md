# 变更记录：营销图片链接卡片

- 日期 / 分支 / worktree: 2026-09-14 / 1.0.3-snapshot / armada、wheel-saas-pure-web 当前工作区
- 需求来源: 用户要求“新加个类型，图片可以跳转链接的”。第一套环境 #114 的图文内容实际发送普通 IMAGE，推广链接只是 caption。
- 状态: 本地实现及验证完成，未部署

## 目标
新增独立“图片链接卡片”类型，复用现有 LINK_CARD 协议，让运营明确选择带图片的链接卡片。

## 缺口拆解 / 任务清单
- [x] 核实 #114 图文类型、当前 composer 和 Web/Android LINK_CARD 通道。
- [x] 新增 IMAGE_LINK(4)，保存要求图片、标题、HTTP(S) 推广链接；发送缺少有效图片或 URL 必须失败。
- [x] 前端创建、编辑、筛选、标签及卡片预览；共享剧本编辑器同步类型。
- [x] 聚焦测试、前端类型检查、构建与代码检查。

## 关键设计决策
- 设计: `docs/business/marketing-image-link.md`。
- API 的 linkMode 增加 4，既有 1/2/3 行为不变。下游消息仍为 LINK_CARD，不增加协议命令。
- 复用 imageFileId/content/bodyText/promotionLink；link_mode 为无枚举约束 TINYINT，无需 DDL/数据迁移。租户、素材归属、锁定校验和任务状态机不变；Redis 无改动。
- 不将普通图文自动升级为链接卡片，不把缺图/无效链接降级为纯文本或图片。
- 发布顺序：后端先于前端。回滚前先停用新类型任务，避免旧后端不认识 4；不能把 4 静默改成 3。

## 验证
- TDD：后端新增 6 项在实现前均因不认识类型 4 失败；前端新增保存、缺项阻止、类型读取/筛选共 3 项在实现前失败。
- `mvn -q -Dtest='MarketingMessageComposerTest,MarketingTemplateServiceImplTest,MarketingMessageCommandFactoryTest,MarketingRoundWorkerTest,MarketingTemplateConverterTest,ScriptMarketingContentTest,WebMessageSendBackendTest,AndroidMessageSendBackendTest' test`：87 项通过，0 失败/跳过。日志 `/private/tmp/marketing-image-link-backend-green.log`。
- `mvn -q -Dtest='MarketingImageLinkMapperH2Test,ResourceAssetMapperH2Test' test`：22 项通过；使用真实 Mapper XML、生产租户插件、Spring 事务及 H2 MySQL 模式。新增 2 项验证类型 4 保存/筛选、读取后组装卡片、跨租户不可见及回滚保持旧 URL。日志 `/private/tmp/marketing-image-link-h2.log`。
- Web 协议 `npm run test:unit -- --runInBand --runTestsByPath src/messages/card-content.test.ts`：5 项通过。
- Android 协议 `go test ./internal/service/node -run '^TestBuildLinkCard' -count=1`：通过。
- 前端使用项目 node-test-loader 运行营销素材、营销任务、剧本表单及 API 相关测试：134 项通过。`pnpm typecheck`、改动文件 ESLint、构建通过。
- 本地真实 Vue/Element Plus 组件静态预览确认新类型选项、图片/链接必填标记、独立卡片样式；点击图片卡片后浏览器新页 URL 为 `https://example.com/card`。截图 `/private/tmp/marketing-image-link-preview.png`。这是本地样式及链接交互验证，不是 WhatsApp 收件端验收。

## 部署
- 用户已授权部署第一套环境（test1），发布后端与前端；不修改 #114 或重发消息。发布脚本测试通过；生产打包测试因仓库缺少 prod/scripts/inspect-production-host.sh 失败，与本次 test1 发布无关。

## 遗留 / 跟进
- 部署后使用获授权的测试账号和群，分别在 WhatsApp Web/iPhone 验证图片区域点击打开指定 URL。
