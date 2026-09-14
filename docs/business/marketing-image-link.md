# 营销图片链接卡片

新增营销消息类型 `IMAGE_LINK(4)`，前端名称“图片链接卡片”。图片、卡片标题、有效 HTTP(S) 推广链接必填，正文作为卡片说明可选，无消息按钮。

保存沿用营销模板 API、素材归属与锁定校验。发送使用 MarketingMessageComposer 的 LINK_CARD 结构：URL 为消息文本及卡片链接，content 为标题，bodyText 为描述，imageFileId 对应图片为缩略图。缺少真实图片字节或有效链接时抛业务错误，不降级。

前端营销模板创建/编辑/筛选/预览、任务修改素材、共享剧本素材编辑入口均识别新类型。既有普通超链、按钮超链、图文内容保持原语义。

Web 复用 `buildLinkCardContent` 的 linkPreview；Android 复用 AndroidMessageSendBackend 与 BuildLinkGroupPayload 的现有卡片实现。无需新消息协议或数据库字段。后端发布后再发布前端；回滚前停用引用新类型的任务。

验收分层：保存/读取类型 4、缺项拒绝、生成 LINK_CARD、原类型回归是本地验证范围。图片点击后的 WhatsApp 实际行为需在已部署环境与收件端验证，不能用模拟预览替代。
