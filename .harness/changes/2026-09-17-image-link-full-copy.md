# 图片链接卡片完整文案

- 日期：2026-09-17
- 需求：用户要求先调整「图片链接卡片」，并确认目标是「解决标题或文案显示不完整」。
- 状态：本地代码与相关验证完成；未提交、未推送、未部署、未真实发送或点击。

## 范围与事实

本次是素材管理 / 营销模版的 `IMAGE_LINK`（LinkMode=4），由 MarketingMessageComposer 生成 LINK_CARD。不同于超链营销的普通按钮（messageType=3）、卡片按钮（messageType=4），也不修改超链营销的单图文（messageType=1）。两种按钮的此前本地候选暂缓发布。

旧实现将完整 content 放入链接预览 title、bodyText 放入 description，但正文 text 仅为网址。能确认这种结构使完整文案依赖预览区域的显示；本次没有接收端解码证据，不能声称已确定某一手机版本的裁剪原因。

## 调整

- 仅显式 IMAGE_LINK：复用现有 composeText，将 content、bodyText、推广网址按原顺序换行拼成正文。保留内部换行、表情与完整内容，不截断；沿用已有 4096 字符检查。
- 预览 title 改为经校验推广 URL 的 host，description 置空，避免整段文案在预览与正文重复。
- 保留 LINK_CARD 类型、目标 URL、缩略图和 mentionAll 参数；未修改协议层的媒体上传、链接匹配或加密逻辑。Web / Android 协议的标题必填要求仍由非空 host 满足。
- 不改数据库字段或已保存模板。普通超链的带图兼容路径继续保持既有行为。
- 前端 IMAGE_LINK 预览显示可点击图片和域名，完整文案在卡片外正文，使用自动换行与保留换行样式；编辑器说明同步更新。API 入参与模板字段不变。

## 修改文件

后端：MarketingMessageComposer.java、MarketingMessageComposerTest.java、MarketingImageLinkMapperH2Test.java。

前端 wheel-saas-pure-web：src/views/material/marketing-template/components/MarketingTemplatePreview.vue、MarketingTemplateDrawer.vue。

## 验证

先修改期望并增加长文案回归用例，`mvn -q -Dtest=MarketingMessageComposerTest test` 实测 14 项中 2 项失败：旧正文只有 URL。实现后执行：

```sh
cd armada-api
mvn -q -Dtest=MarketingMessageComposerTest,MarketingImageLinkMapperH2Test,MarketingTemplateServiceImplTest,MarketingRoundWorkerTest,AndroidMessageSendBackendTest,WebMessageSendBackendTest -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar test
```

6 个类分别 14、2、26、19、17、6 项，共 84 项，0 failure/error/skipped，exit 0。H2 用真实 Mapper 读回模板后验证新正文与原字段不变；不是线上库验收。

前端：

```sh
node --import tsx --import ./src/api/__tests__/node-test-alias.mjs --test src/views/material/marketing-template/components/MarketingTemplatePreview.test.ts src/views/material/marketing-template/components/MarketingTemplateDrawer.test.ts src/views/material/marketing-template/composables/useMarketingTemplatePage.test.ts
pnpm typecheck
pnpm build
pnpm exec eslint src/views/material/marketing-template/components/MarketingTemplatePreview.vue src/views/material/marketing-template/components/MarketingTemplateDrawer.vue
pnpm exec prettier --check src/views/material/marketing-template/components/MarketingTemplatePreview.vue src/views/material/marketing-template/components/MarketingTemplateDrawer.vue
pnpm exec stylelint src/views/material/marketing-template/components/MarketingTemplatePreview.vue src/views/material/marketing-template/components/MarketingTemplateDrawer.vue
```

34 项前端测试与以上检查均 exit 0；两个仓库 git diff --check 通过。最初未使用仓库的 node-test-alias 注册器，composable 测试在导入 nprogress.css 时失败；改用已有注册器后 34 项全部执行通过，没有修改生产代码绕过测试。

另用当前 Vue 组件的 Vite SSR 渲染合成示例，断言长文案、空行、表情、末尾标记与完整 URL 均在正文、卡片标题只显示域名、图片锚点仍为目标 URL，断言通过。该临时 SSR 运行同时出现沙箱不允许开发服务器 WebSocket 监听和依赖扫描告警；不能当作浏览器截图或手机验收。正式前端构建正常完成。

## 发布与验收边界

- 只发布本记录的文件及必要依赖，不带入此前按钮候选和共享工作区的账号/注册等改动。前后端需同批发布，否则预览与真实发送不一致。
- 现有模板不需迁移；发布后 IMAGE_LINK 新生成的消息采用新布局，已排队的出站 payload 不会被追溯重写。
- 原生客户端可能仍按自己的规则折叠较长正文；本次保证文案完整编码，不保证所有手机首屏无「阅读更多」。
- 实机待验：原有测试手机收到同一条合成文案后，核对首尾、段落和无重复，再点击图片确认目标。真实发送范围需要明确授权，不应重跑批量任务或触发已有推广短链统计。
- 当前未部署，未达到用户手机上的业务验收。回滚只需恢复本次前后端布局代码，无数据迁移。
